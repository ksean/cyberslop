# Cyberslop

Cyberslop is a cyberpunk-dystopian side-scrolling roguelite built with Kotlin and WebAssembly for
modern web browsers. Ten procedurally generated, provably completable maps; four keys; a weapon
that fires by itself; permadeath with persistent unlocks.

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

Requirements and design live in [specs](specs/README.md). Current work is planned in
[plan.md](plan.md) and tracked in [tasks.md](tasks.md).

Licensed under the [Apache License 2.0](LICENSE).
