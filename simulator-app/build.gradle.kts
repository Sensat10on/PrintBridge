plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application { mainClass.set("com.printbridge.simulatorapp.MainKt") }

dependencies {
    implementation(project(":print-core"))
    implementation(project(":simulator-core"))
    implementation(project(":printer-transport"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
