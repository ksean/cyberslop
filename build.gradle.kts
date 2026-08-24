import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrCompilation
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "io.github.ksean.cyberslop"
version = "0.1.0-SNAPSHOT"

kotlin {
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
    description = "Smoke-tests the production title screen bundle."
    dependsOn(tasks.named("wasmJsBrowserDistribution"))
    inputs.file(productionBundle)
    inputFileProperty.set(layout.projectDirectory.file("scripts/title-screen-smoke.cjs"))
    args(productionBundle.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(titleScreenSmokeTest)
}
