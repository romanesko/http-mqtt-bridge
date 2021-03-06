import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.10"
    id("application")
    id("com.github.johnrengelman.shadow") version "7.0.0"
}

application {
    mainClass.set("MainKt")
}

group = "me.roman"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("io.javalin:javalin:3.13.10")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.10.3") // Necessary for serializing JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.10.3") // Necessary for serializing JSON
    implementation("org.slf4j:slf4j-simple:1.7.30") // Necessary to view logging output
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")


}

//tasks.jar {
//    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
//}


tasks.test {
    useJUnit()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "1.8"
}


