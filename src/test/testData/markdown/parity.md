# Parity with Obsidian

Live markup should show this file the way Obsidian's live preview does, heading sizes aside.

## Inline markup

Some **bold**, some *italic*, some ***both***, some ~~struck~~ and some `inline code` in one line.
Underscores work too: _italic_ and __bold__, and `` a ` b `` keeps its inner backtick.

## Links

A [link with text](https://example.com/page), a [file link](collector.md), a [location](../links/md/src/Foo.php:3:5) and a [heading link](#inline-markup).
Reference links stay raw: [ref text][ref]. Images stay raw: ![alt text](image.png).

[ref]: https://example.com/ref

## Lists

- a bullet with **bold**
- [ ] an open task
- [x] a done task
* another bullet style
+ and the third
1. a numbered item with *emphasis*
2. another one

> A quote with **bold** and `code`.

```php
**not markup** inside a fence, `nor` this
```

    **not markup** in an indented block either

| Column | **Bold** |
|--------|----------|
| *cell* | `code`   |
