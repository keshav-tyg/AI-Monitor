description = "Persistent storage for strict-mode data."

dependencies {
    implementation(project(":strict-core"))
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}
