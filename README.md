# Kensington Plugin

CSS class name completion for the Kensington JS template engine in RubyMine.

## Manual Testing

### Setup

1. Run `./gradlew runIde` from the plugin project root. This opens a sandboxed RubyMine instance.
2. Open `../kensington-plugin-test` in the sandbox IDE.
3. Open `index.js`.

Wait for the background task "Kensington: updating CDN styles" to finish in the bottom-right progress indicator before testing CDN-sourced completions.

---

### 1. Local CSS classes

In `index.js`, place the cursor inside an empty `class` string:

```js
t.h1({ class: '' }, 'Hello, World!')
```

Trigger completion (default: `Ctrl+Space`). Expect to see classes from `public/css/styles.css`:
- `some-class`
- `another-class`
- `third-class`

---

### 2. CDN classes — CSS `@import`

`styles.css` imports Materialize via `@import "https://..."`. After the initial fetch, triggering completion inside a `class` string should include Materialize classes such as `btn`, `card`, `container`, `waves-effect`.

---

### 3. CDN classes — Kensington template `t.link`

`index.js` references Bootstrap via `t.link({ href: 'https://cdn.jsdelivr.net/...' })`. Completion should include Bootstrap classes such as `btn`, `btn-primary`, `col`, `row`, `container`.

---

### 4. Multiple classes in one string

Type a class, then a space, then begin a second class:

```js
t.main({ class: 'container col' })
//                            ^ cursor here
```

Completions should still appear filtered to the word after the last space. Selecting a suggestion should insert only the new word, not replace the whole string.

---

### 5. Commented-out imports are ignored

In `index.js`, comment out the Bootstrap `t.link` call:

```js
// t.link({ href: 'https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css' })
```

Save the file. Wait for "Kensington: updating CDN styles" to finish. Bootstrap-only classes (e.g. `btn-outline-primary`) should no longer appear in completions.

Uncomment the line and save to restore them.

---

### 6. Changing CDN framework

In `styles.css`, replace the Materialize `@import` with a different CDN stylesheet URL and save. Expect:
- Materialize classes disappear.
- Classes from the new stylesheet appear after the fetch completes.

---

### 7. Removing a CDN import entirely

Delete the Bootstrap `t.link` line from `index.js` and save. After the background task finishes, Bootstrap classes should no longer appear.

Re-add the line and save to restore.
