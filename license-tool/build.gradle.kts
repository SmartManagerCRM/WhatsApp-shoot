plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

application {
    mainClass.set("com.smartmanager.whatsappshoot.licensetool.MainKt")
}

dependencies {
    implementation(project(":license-core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":license-core")))
}
