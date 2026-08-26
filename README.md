# Cyberslop

Cyberslop is a dystopian side-scrolling adventure game being built with Kotlin and WebAssembly for modern web browsers. It is in early development. The design and its verification strategy are recorded in
[plan.md](plan.md).

## Run locally

Install any JDK 17–26 — the build pins its own toolchain and downloads a matching JDK if yours is
outside that range — then run:

```bash
./gradlew wasmJsBrowserDevelopmentRun
```

Run the project checks with:

```bash
./scripts/check.sh
```

If that fails to download `binaryen`, see [scripts/local-binaryen.md](scripts/local-binaryen.md).

Project requirements and the two-phase development workflow live in [specs](specs/README.md). Pending implementation work is tracked in [tasks.md](tasks.md).

Licensed under the [Apache License 2.0](LICENSE).
