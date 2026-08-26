import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrCompilation
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "io.github.ksean.cyberslop"
version = "0.1.0-SNAPSHOT"

kotlin {
    // Verification-only target (ENG-001). Common tests run on every declared target, and the browser
    // test runner caps a single test at 2000 ms, which the map-generation seed sweeps cannot fit
    // inside. Those live in `jvmTest` and run here. Nothing is deployed from this target.
    jvm()

    jvmToolchain(21)

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useFirefoxHeadless()
                }
            }
        }
        binaries.executable()

        compilerOptions {
            freeCompilerArgs.add("-Xwasm-enable-array-range-checks")
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val productionBundle = layout.buildDirectory.file(
    "dist/wasmJs/productionExecutable/cyberslop.js",
)
val wasmJsMain = kotlin.targets
    .getByName("wasmJs")
    .compilations
    .getByName("main") as KotlinJsIrCompilation

val titleScreenSmokeTest = NodeJsExec.register(wasmJsMain, "titleScreenSmokeTest") {
    group = "verification"
    description = "Smoke-tests the production bundle: title screen, starting a run, and asset paths."
    dependsOn(tasks.named("wasmJsBrowserDistribution"))
    inputs.file(productionBundle)
    inputFileProperty.set(layout.projectDirectory.file("scripts/title-screen-smoke.cjs"))
    args(productionBundle.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(titleScreenSmokeTest)
}
