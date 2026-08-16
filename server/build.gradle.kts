plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.moovie"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("com.moovie.ApplicationKt")
}

dependencies {
    implementation(project(":plugin-runtime"))
    implementation(project(":library"))
    implementation(project(":common"))

    implementation("io.ktor:ktor-server-core-jvm:2.3.11")
    implementation("io.ktor:ktor-server-cors-jvm:2.3.11")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.11")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}