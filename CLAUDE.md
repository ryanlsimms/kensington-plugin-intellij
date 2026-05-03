# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew compileKotlin       # compile only (fast feedback)
./gradlew runIde              # build and launch a sandboxed RubyMine instance with the plugin loaded
./gradlew buildPlugin         # produce a distributable .zip in build/distributions/
```

There are no automated tests in this project. Validation is done by running `runIde` and testing manually in the sandbox IDE against the `kensington-plugin-test` project at `../kensington-plugin-test`.

## Architecture

This is an IntelliJ Platform plugin (Gradle plugin 2.x) targeting RubyMine 2024.3+ (build 243–251). It provides CSS class name completion for the Kensington JS template engine, where class names are written as `{ class: 'foo bar' }` or `{ class: ['foo', 'bar'] }`.

### Completion pipeline

**`KensingtonCompletionContributor`** — registers the provider for any PSI element inside a `JSLiteralExpression` using `.inside()`. Registered for both JavaScript and TypeScript in `plugin.xml`.

**`CssClassNameProvider`** — the `CompletionProvider` that runs when the contributor pattern matches. Two guards before adding suggestions:
1. `insideClassProperty` — walks up the PSI tree via `PsiTreeUtil.getParentOfType` to confirm the literal is inside a `JSProperty` named `"class"`.
2. `currentWordPrefix` — strips the leading quote and splits on spaces so the prefix matcher reflects only the word being typed, not the entire string content. This is necessary for multi-class strings like `'btn b|'`.

Merges results from two sources: local CSS files (scanned via `VfsUtil.iterateChildrenRecursively` on the project root) and CDN-fetched CSS (`CdnCssCache`).

**`CdnCssCache`** — a `@Service(Level.PROJECT)` implementing `Disposable` that detects CDN CSS URLs and caches fetched content on disk.
- On `init`: creates `<IntelliJ system dir>/kensington/cdn-css/` and loads cached class names from disk. Also subscribes to `VFS_CHANGES` via the project message bus (connection tied to service lifetime via `connect(this)`) to re-scan whenever a relevant file is saved.
- `triggerRefresh()`: guards against concurrent runs with an `AtomicBoolean`, then queues a `Task.Backgroundable` which shows "Kensington: updating CDN styles" in the IDE's background progress indicator with per-URL subtitle and proportional progress.
- `refresh()`: computes the current set of CDN URLs from scanned files, deletes cached files for URLs no longer present (handles removed/changed links), fetches stale entries (TTL 24h), and reloads the in-memory `classNames` set if anything changed.
- CDN URL detection scans HTML, CSS, SCSS, Less, JS, TS, MJS, and CJS files for `https://...*.css` URLs — covering `<link href="...">` in HTML, `@import url("...")` in CSS, and `t.link({ href: '...' })` in Kensington templates. Comments are stripped before scanning (`<!-- -->` for HTML, `/* */` for CSS, `/* */` and `(?<!:)//` for JS/TS) so commented-out imports are ignored. The `(?<!:)` lookbehind is required to avoid stripping `https://` URLs when removing JS line comments.
- `getClassNames()`: returns the current in-memory set — always fast, safe to call on any thread.

**`KensingtonStartupActivity`** — `StartupActivity.DumbAware` that calls `CdnCssCache.triggerRefresh()` on project open.

### Key constraints

- `currentWordPrefix` must strip the opening quote *and* split on spaces — without it, IntelliJ's default prefix matcher treats `'btn b` as the prefix and no class names match.
- `VfsUtil.iterateChildrenRecursively` is called inside `runReadAction` since it accesses the VFS from a background thread.
- The contributor uses `.inside(JSLiteralExpression::class.java)` rather than `.withParent()` — `parameters.position` is a leaf token and `.withParent` is too strict.
