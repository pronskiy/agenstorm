package com.pronskiy.agenstorm.terminal

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.links.FileLocation
import com.pronskiy.agenstorm.links.FileLocationResolver
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Step G1.1. The endpoint the terminal `open` shim (G1.3) posts to: one loopback [HttpServer] per project,
 * bound on a free port so it is unreachable from outside this machine, plus a token generated per IDE run.
 *
 * The body of `POST /open` is a NUL-separated UTF-8 list — the token, the shell's `$PWD`, then the original
 * argv — so a path containing spaces, quotes or newlines needs no encoding on the shell side. The token is a
 * body field rather than a header because a header would have to be a `curl` argument, and on Linux
 * `/proc/<pid>/cmdline` is world-readable: any local user could read the token out of it.
 *
 * Answers `204` when the IDE claimed the command, `409` when it did not (the shim then execs the real `open`),
 * `403` on a bad or missing token. Nothing is bound until [start] is called, which only happens while
 * `terminalOpenEnabled` is on; the server is closed with the project.
 */
@Service(Service.Level.PROJECT)
class OpenRequestServer(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /** Per-IDE-run secret the shim sends back as the first body field; a request without it is refused. */
    val token: String = randomHex(TOKEN_HEX_CHARS)

    /** Decides and performs one `open` invocation. Runs off the EDT, outside a read action; tests replace it. */
    @Volatile
    var handler: OpenRequestHandler = OpenRequestHandler { cwd, argv -> perform(cwd, argv) }

    @Volatile
    private var server: HttpServer? = null

    /** The bound port, or -1 while the endpoint is not running. */
    val port: Int
        get() = server?.address?.port ?: -1

    /**
     * Binds the endpoint if it is not bound yet and returns its port, or -1 when binding failed.
     * Idempotent and safe to call from any thread.
     */
    @Synchronized
    fun start(): Int {
        server?.let { return it.address.port }
        val bound = try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        } catch (e: IOException) {
            LOG.warn("Agenstorm: could not bind the terminal open endpoint for ${project.name}", e)
            return -1
        }
        // The dispatcher thread only starts a coroutine; reading the body and answering happen in the
        // service scope, so a slow decision never blocks the next request and project close cancels it.
        bound.createContext(CONTEXT_PATH) { exchange ->
            scope.launch { serve(exchange) }
        }
        bound.start()
        server = bound
        LOG.debug("Agenstorm: terminal open endpoint listening on 127.0.0.1:${bound.address.port}")
        return bound.address.port
    }

    /** Closes the endpoint if it is running. Idempotent. */
    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    override fun dispose() {
        stop()
    }

    private suspend fun serve(exchange: HttpExchange) {
        try {
            val status = try {
                dispatch(exchange)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The shim must always get an answer: without one it waits for its own timeout instead of
                // falling straight through to the real `open`.
                LOG.warn("Agenstorm: terminal open request failed", e)
                HTTP_SERVER_ERROR
            }
            exchange.sendResponseHeaders(status, NO_BODY)
        } catch (e: IOException) {
            LOG.debug("Agenstorm: could not answer a terminal open request", e)
        } finally {
            exchange.close()
        }
    }

    private suspend fun dispatch(exchange: HttpExchange): Int {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) return HTTP_METHOD_NOT_ALLOWED
        val body = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return HTTP_PAYLOAD_TOO_LARGE
        val parsed = parseBody(body) ?: return HTTP_BAD_REQUEST
        if (!isTokenValid(parsed.token)) return HTTP_FORBIDDEN
        val request = parsed.request
        return if (handler.handle(request.cwd, request.argv)) HTTP_NO_CONTENT else HTTP_CONFLICT
    }

    /**
     * Step G2.1. Routes the invocation and, when the IDE claims it, opens every file it named.
     * Returns false for anything the shim should hand to the real `open`.
     */
    private suspend fun perform(cwd: Path, argv: List<String>): Boolean {
        val state = AgenstormSettings.getInstance().state
        if (!state.terminalOpenEnabled || project.isDisposed) return false
        return when (val decision = OpenCommandRouter(project, state.terminalOpenUnknownFileTypes).route(cwd, argv)) {
            is OpenCommandRouter.Decision.OpenFiles -> openFiles(decision.targets)
            // G2.2 takes the directory branch; until then a directory goes to the OS.
            is OpenCommandRouter.Decision.OpenProject -> false
            is OpenCommandRouter.Decision.Fallback -> {
                LOG.debug("Agenstorm: leaving `open` to the OS (${decision.reason})")
                false
            }
        }
    }

    private suspend fun openFiles(targets: List<OpenCommandRouter.FileTarget>): Boolean {
        if (targets.isEmpty()) return false
        withContext(Dispatchers.EDT) {
            val resolver = FileLocationResolver(project)
            for (target in targets) {
                descriptorFor(resolver, target).navigate(true)
            }
            // Once, after the last file: the window the terminal lives in comes forward.
            ProjectUtil.focusProjectWindow(project, true)
        }
        return true
    }

    /**
     * The caret position is computed the way Epic A computes it for a written location, so a line past the
     * end of the file lands on the last line instead of failing.
     */
    private fun descriptorFor(resolver: FileLocationResolver, target: OpenCommandRouter.FileTarget): OpenFileDescriptor {
        val line = target.line ?: return OpenFileDescriptor(project, target.file)
        val location = FileLocation(target.file.path, line, target.column)
        return OpenFileDescriptor(project, target.file, resolver.toOffset(target.file, location))
    }

    private fun isTokenValid(presented: String): Boolean {
        return MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            token.toByteArray(StandardCharsets.UTF_8),
        )
    }

    /** One `open` invocation as it left the shell: the shell's working directory and the untouched argv. */
    data class Request(val cwd: Path, val argv: List<String>)

    /** A well-formed body: the token it presented and the invocation it describes. */
    data class ParsedBody(val token: String, val request: Request)

    companion object {
        const val CONTEXT_PATH: String = "/open"

        /** The two variables [com.pronskiy.agenstorm.terminal.TerminalOpenExecOptionsCustomizer] puts into a
         *  terminal's environment; without [PORT_ENV] the shim claims nothing at all. */
        const val PORT_ENV: String = "AGENSTORM_OPEN_PORT"
        const val TOKEN_ENV: String = "AGENSTORM_OPEN_TOKEN"

        /** macOS `ARG_MAX` is 1 MiB, so a legitimate argv can never be larger than this. */
        const val MAX_BODY_BYTES: Int = 1 shl 20

        private const val TOKEN_HEX_CHARS = 32
        private const val NO_BODY = -1L
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_CONFLICT = 409
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_SERVER_ERROR = 500

        private val LOG = logger<OpenRequestServer>()

        /**
         * Splits a NUL-separated body into the token, `$PWD` and argv. The shim's
         * `printf '%s\0' "$AGENSTORM_OPEN_TOKEN" "$PWD" "$@"` leaves a trailing NUL, which is dropped; every
         * remaining field is kept verbatim, empty arguments included. Returns null when the body does not
         * carry at least a token and a usable working directory.
         */
        fun parseBody(body: ByteArray): ParsedBody? {
            val fields = String(body, StandardCharsets.UTF_8).removeSuffix("\u0000").split('\u0000')
            if (fields.size < 2) return null
            val cwd = fields[1].takeIf { it.isNotBlank() } ?: return null
            val path = try {
                Path.of(cwd)
            } catch (_: InvalidPathException) {
                return null
            }
            return ParsedBody(fields[0], Request(path, fields.drop(2)))
        }

        private fun randomHex(chars: Int): String {
            val bytes = ByteArray(chars / 2)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * What the IDE does with one `open` invocation. Implemented by the router wiring in G1.2 / G2.1;
 * `true` means the IDE claimed the command, `false` sends the shim to the real `open`.
 */
fun interface OpenRequestHandler {
    suspend fun handle(cwd: Path, argv: List<String>): Boolean
}
