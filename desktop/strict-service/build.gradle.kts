description = "Background service coordination for strict mode."

dependencies {
    implementation(project(":strict-core"))
    implementation(project(":strict-store"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}
