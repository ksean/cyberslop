# Cyberslop

Cyberslop is a dystopian side-scrolling adventure game being built with Kotlin and WebAssembly for modern web browsers. It is in early development and does not yet have a playable build.

## Run locally

Install JDK 17–26, then run:

```bash
./gradlew wasmJsBrowserDevelopmentRun
```

Run the project checks with:

```bash
./scripts/check.sh
```

Project requirements and the two-phase development workflow live in [specs](specs/README.md). Pending implementation work is tracked in [tasks.md](tasks.md).

Licensed under the [Apache License 2.0](LICENSE).
