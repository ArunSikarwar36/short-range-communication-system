import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * Platform-free SRCS core shared by Android (jvm target) and iOS.
 * Only crypto primitives, sockets and a few clock/random/lock helpers differ per platform
 * (jvmMain / iosMain); everything else lives in commonMain and is tested in commonTest on both.
 */
kotlin {
    jvmToolchain(17)
    jvm()
    // The iOS app links SrcsCore.xcframework (gradlew :core:assembleSrcsCoreReleaseXCFramework, macOS only).
    val xcf = XCFramework("SrcsCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.compilerOptions.optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        it.binaries.framework {
            baseName = "SrcsCore"
            isStatic = true
            xcf.add(this)
        }
    }

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.prov)
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Keep the documented `gradlew :core:test` working (KMP names it jvmTest).
tasks.register("test") {
    group = "verification"
    description = "Runs the core tests on the JVM"
    dependsOn("jvmTest")
}

// Desktop test phone for emulator demos (see src/jvmTest/kotlin/.../DemoPeer.kt).
tasks.register<JavaExec>("demoPeer") {
    group = "verification"
    description = "Runs a desktop SRCS peer on TCP 47710 / UDP 47711"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass.set("ai.paai.link.core.DemoPeerKt")
}

// Developer tool: download a map area with the app's extractor (see src/jvmTest/kotlin/.../MapExtract.kt).
tasks.register<JavaExec>("mapExtract") {
    group = "verification"
    description = "Downloads one map area from a Protomaps build into a .pmtiles file"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass.set("ai.paai.link.core.MapExtractKt")
    for (p in listOf("map.lat", "map.lon", "map.km", "map.zoom", "map.out", "map.url")) {
        providers.gradleProperty(p).orNull?.let { systemProperty(p, it) }
    }
}
