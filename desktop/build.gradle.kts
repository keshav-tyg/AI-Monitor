import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaToolchainService

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

val appName = "Local Focus Coach"
val appImageDirectory = layout.buildDirectory.dir("jpackage/$appName.app")
val packageInputDirectory = layout.buildDirectory.dir("jpackage-input")
val packageConfigurationDirectory = layout.buildDirectory.dir("jpackage-config")
val generatedServiceSourceDirectory = layout.buildDirectory.dir("generated/sources/jpackage-service")
val generatedServiceClassesDirectory = layout.buildDirectory.dir("classes/jpackage-service")

val generateServiceBootstrap by tasks.registering {
    outputs.dir(generatedServiceSourceDirectory)
    doLast {
        val source = generatedServiceSourceDirectory.get().file(
            "com/localfocuscoach/strict/service/ServiceMain.java"
        ).asFile
        source.parentFile.mkdirs()
        source.writeText(
            """
            package com.localfocuscoach.strict.service;

            import com.localfocuscoach.strict.protocol.ProtocolCodec;
            import com.localfocuscoach.strict.store.SqliteStrictSessionRepository;
            import java.nio.file.Files;
            import java.time.Clock;

            public final class ServiceMain {
                private ServiceMain() {}

                public static void main(String[] args) throws Exception {
                    var appSupport = InstallSecret.defaultAppSupportDirectory();
                    Files.createDirectories(appSupport);
                    var repository = new SqliteStrictSessionRepository(
                            appSupport.resolve("strict-mode.sqlite3"));
                    var service = new StrictModeService(
                            InstallSecret.loadOrCreateDefault(), repository, new MacChromeController());
                    var server = new UnixSocketServer(
                            UnixSocketServer.defaultSocketPath(),
                            new ProtocolCodec(),
                            service,
                            Clock.systemUTC());
                    Runtime.getRuntime().addShutdownHook(
                            Thread.ofPlatform().unstarted(() -> {
                                server.close();
                                service.close();
                            }));
                    try {
                        server.start();
                        server.awaitTermination();
                    } finally {
                        server.close();
                        service.close();
                    }
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

val compileServiceBootstrap by tasks.registering(JavaCompile::class) {
    dependsOn(generateServiceBootstrap)
    source(generatedServiceSourceDirectory)
    classpath = project(":strict-service").sourceSets.main.get().runtimeClasspath
    destinationDirectory.set(generatedServiceClassesDirectory)
    options.release.set(21)
}

val serviceBootstrapJar by tasks.registering(Jar::class) {
    dependsOn(compileServiceBootstrap)
    archiveFileName.set("local-focus-coach-service-launcher.jar")
    from(generatedServiceClassesDirectory)
}

val prepareJpackageLaunchers by tasks.registering {
    outputs.dir(packageConfigurationDirectory)
    doLast {
        val directory = packageConfigurationDirectory.get().asFile
        directory.mkdirs()
        directory.resolve("service.properties").writeText(
            """
            main-jar=local-focus-coach-service-launcher.jar
            main-class=com.localfocuscoach.strict.service.ServiceMain
            description=Local Focus Coach Strict Mode background service
            """.trimIndent() + "\n"
        )
        directory.resolve("relay.properties").writeText(
            """
            main-jar=strict-relay.jar
            main-class=com.localfocuscoach.strict.relay.NativeMessagingRelay
            description=Local Focus Coach Chrome Native Messaging relay
            """.trimIndent() + "\n"
        )
    }
}

val packagedLogging = configurations.detachedConfiguration(
    dependencies.create("org.slf4j:slf4j-nop:1.7.36")
)

val prepareJpackageInput by tasks.registering(Sync::class) {
    dependsOn(serviceBootstrapJar)
    into(packageInputDirectory)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(serviceBootstrapJar)
    from(packagedLogging)
    listOf(":strict-dashboard", ":strict-relay", ":strict-service").forEach { projectPath ->
        from(project(projectPath).tasks.named("jar"))
        from(project(projectPath).configurations.named("runtimeClasspath"))
    }
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val java21Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Creates a local macOS app image with a private Java 21 runtime."
    dependsOn(prepareJpackageInput, prepareJpackageLaunchers)
    inputs.dir(packageInputDirectory)
    inputs.dir(packageConfigurationDirectory)
    outputs.dir(appImageDirectory)

    doFirst {
        val destination = layout.buildDirectory.dir("jpackage").get().asFile
        val appImage = appImageDirectory.get().asFile
        delete(appImage)
        destination.mkdirs()
        val jpackage = java21Launcher.get().metadata.installationPath.file("bin/jpackage").asFile
        val configuration = packageConfigurationDirectory.get().asFile
        commandLine(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", appName,
            "--dest", destination.absolutePath,
            "--input", packageInputDirectory.get().asFile.absolutePath,
            "--main-jar", "strict-dashboard.jar",
            "--main-class", "com.localfocuscoach.strict.dashboard.DashboardApp",
            "--add-launcher",
            "Local Focus Coach Service=${configuration.resolve("service.properties").absolutePath}",
            "--add-launcher",
            "Local Focus Coach Relay=${configuration.resolve("relay.properties").absolutePath}",
            "--add-modules",
            "java.base,java.desktop,java.logging,java.management,java.naming,java.sql,jdk.unsupported",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--app-version", "1.0.0",
            "--vendor", "Local Focus Coach",
            "--description", "Local macOS companion for opt-in Strict Mode sessions",
            "--mac-package-identifier", "com.localfocuscoach.strict-mode",
            "--mac-package-name", "Focus Coach"
        )
    }
}
