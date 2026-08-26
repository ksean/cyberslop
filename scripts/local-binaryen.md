# Working around an unreachable binaryen download

## Symptom

`./scripts/check.sh` fails during configuration with:

```
Could not determine the dependencies of task ':kotlinWasmBinaryenSetup'.
> Could not resolve com.github.webassembly:binaryen:125.
   > release-assets.githubusercontent.com: Name or service not known
```

The Kotlin/Wasm plugin downloads `binaryen` to optimise the production `.wasm`. GitHub redirects that
download to `release-assets.githubusercontent.com`, which does not resolve on some networks. CI is
unaffected.

## Fix

Install the **same version the build asks for** — currently **125**, and it must match, because
optimising the shipped wasm with a different `wasm-opt` than CI uses is exactly the toolchain drift
the determinism requirements exist to prevent (ENG-054).

```bash
# Resolve the redirect target explicitly, then fetch through it.
IP=$(curl -sS -H 'accept: application/dns-json' \
  'https://1.1.1.1/dns-query?name=release-assets.githubusercontent.com&type=A' \
  | grep -o '"data":"[0-9.]*"' | head -1 | cut -d'"' -f4)

curl -sSL --resolve "release-assets.githubusercontent.com:443:$IP" \
  -o /tmp/binaryen.tar.gz \
  https://github.com/WebAssembly/binaryen/releases/download/version_125/binaryen-version_125-x86_64-linux.tar.gz

mkdir -p ~/.local/binaryen-125
tar -xzf /tmp/binaryen.tar.gz -C ~/.local/binaryen-125 --strip-components=1
~/.local/binaryen-125/bin/wasm-opt --version    # must print: wasm-opt version 125
```

Then point Gradle at it with an init script, which keeps the workaround out of the repository so CI
and other contributors are untouched:

```bash
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/local-binaryen.gradle <<'GROOVY'
def localWasmOpt = new File(System.getProperty('user.home'), '.local/binaryen-125/bin/wasm-opt')

gradle.rootProject { root ->
    if (!localWasmOpt.isFile()) return
    root.afterEvaluate {
        root.extensions.extensionsSchema.each { schema ->
            if (!schema.name.toLowerCase().contains('binaryen')) return
            def ext = root.extensions.findByName(schema.name)
            if (ext == null || !ext.getClass().name.contains('EnvSpec')) return
            try {
                ext.download.set(false)
                ext.command.set(localWasmOpt.absolutePath)
                root.logger.lifecycle("[local-binaryen] ${schema.name} -> ${localWasmOpt}")
            } catch (Throwable t) {
                root.logger.lifecycle("[local-binaryen] skipped ${schema.name}: ${t.message}")
            }
        }
    }
}
GROOVY
```

The npm `binaryen` package is **not** a substitute: its `bin/wasm-opt` is a Node wrapper script, not
the native binary Gradle needs.
