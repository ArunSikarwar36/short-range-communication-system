plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Desktop test phone for emulator demos (see src/test/kotlin/.../DemoPeer.kt).
tasks.register<JavaExec>("demoPeer") {
    group = "verification"
    description = "Runs a desktop SRCS peer on TCP 47710 / UDP 47711"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ai.paai.link.core.DemoPeerKt")
}
