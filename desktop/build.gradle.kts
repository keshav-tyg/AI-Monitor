plugins { java }

allprojects {
    repositories { mavenCentral() }
}

subprojects {
    plugins.apply("java")
    java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
    tasks.test { useJUnitPlatform() }
}
