plugins { java }

val javaFxVersion = "21.0.5"

allprojects {
    extra["javaFxVersion"] = javaFxVersion
    repositories { mavenCentral() }
}

subprojects {
    plugins.apply("java")
    java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
    tasks.test { useJUnitPlatform() }
}
