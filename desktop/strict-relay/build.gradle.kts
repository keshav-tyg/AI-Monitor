description = "Local relay integration for strict mode."

dependencies {
    implementation(project(":strict-service"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}
