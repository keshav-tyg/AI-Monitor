plugins { application }

description = "Desktop dashboard presentation for strict mode."

val javaFxClassifier = when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "mac-aarch64"
    else -> "mac"
}

dependencies {
    implementation(project(":strict-core"))
    implementation(project(":strict-service"))
    implementation(
        "org.openjfx:javafx-base:${rootProject.extra["javaFxVersion"]}:$javaFxClassifier"
    )
    implementation(
        "org.openjfx:javafx-graphics:${rootProject.extra["javaFxVersion"]}:$javaFxClassifier"
    )
    implementation(
        "org.openjfx:javafx-controls:${rootProject.extra["javaFxVersion"]}:$javaFxClassifier"
    )

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

application {
    mainClass.set("com.localfocuscoach.strict.dashboard.DashboardApp")
}
