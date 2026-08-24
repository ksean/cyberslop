import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()

        compilerOptions {
            freeCompilerArgs.add("-Xwasm-enable-array-range-checks")
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
