package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FirstRunBootstrapTest {
    @Test
    void skipsWhenNativeHostManifestAlreadyExists(@TempDir Path root) throws IOException {
        Path home = root.resolve("home");
        Path nativeHosts = home.resolve(
                "Library/Application Support/Google/Chrome/NativeMessagingHosts");
        Files.createDirectories(nativeHosts);
        Files.writeString(nativeHosts.resolve(FirstRunBootstrap.PROD_MANIFEST), "{}");
        Path bundle = createBundleWithInstallerAndIdentity(root);
        var log = new ArrayList<String>();
        var invocations = new AtomicInteger();

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> {
                    invocations.incrementAndGet();
                    return 0;
                },
                log::add);

        assertEquals(FirstRunBootstrap.Result.ALREADY_REGISTERED, result);
        assertEquals(0, invocations.get());
        assertTrue(log.stream().anyMatch(entry -> entry.contains("already registered")));
    }

    @Test
    void skipsWhenDevelopmentManifestAlreadyExists(@TempDir Path root) throws IOException {
        Path home = root.resolve("home");
        Path nativeHosts = home.resolve(
                "Library/Application Support/Google/Chrome/NativeMessagingHosts");
        Files.createDirectories(nativeHosts);
        Files.writeString(nativeHosts.resolve(FirstRunBootstrap.DEV_MANIFEST), "{}");
        Path bundle = createBundleWithInstallerAndIdentity(root);
        var invocations = new AtomicInteger();

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> {
                    invocations.incrementAndGet();
                    return 0;
                },
                message -> {});

        assertEquals(FirstRunBootstrap.Result.ALREADY_REGISTERED, result);
        assertEquals(0, invocations.get());
    }

    @Test
    void skipsWhenNoBundlePath(@TempDir Path root) {
        Path home = root.resolve("home");
        var log = new ArrayList<String>();

        var result = FirstRunBootstrap.run(
                null,
                home,
                (command, workingDirectory) -> {
                    throw new AssertionError("Installer must not run without a bundle");
                },
                log::add);

        assertEquals(FirstRunBootstrap.Result.NO_BUNDLE_PATH, result);
        assertTrue(log.stream().anyMatch(entry -> entry.contains("not running from a packaged")));
    }

    @Test
    void skipsWhenBundleDirectoryDoesNotExist(@TempDir Path root) {
        Path home = root.resolve("home");
        Path bundle = root.resolve("nonexistent/Local Focus Coach.app");

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> {
                    throw new AssertionError("Installer must not run without a bundle");
                },
                message -> {});

        assertEquals(FirstRunBootstrap.Result.NO_BUNDLE_PATH, result);
    }

    @Test
    void skipsWhenPathIsNotAnAppBundle(@TempDir Path root) throws IOException {
        Path home = root.resolve("home");
        Path notABundle = root.resolve("Local Focus Coach");
        Files.createDirectories(notABundle);

        var result = FirstRunBootstrap.run(
                notABundle,
                home,
                (command, workingDirectory) -> {
                    throw new AssertionError("Installer must not run without a bundle");
                },
                message -> {});

        assertEquals(FirstRunBootstrap.Result.NO_BUNDLE_PATH, result);
    }

    @Test
    void reportsMissingInstallerScript(@TempDir Path root) throws IOException {
        Path home = root.resolve("home");
        Path bundle = root.resolve("Local Focus Coach.app");
        Files.createDirectories(bundle.resolve("Contents/Resources"));
        Files.writeString(
                bundle.resolve("Contents/Resources/production-extension-identity.json"),
                "{\"version\":1}");

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> {
                    throw new AssertionError("Installer must not run when script is missing");
                },
                message -> {});

        assertEquals(FirstRunBootstrap.Result.MISSING_INSTALLER, result);
    }

    @Test
    void reportsMissingIdentityFile(@TempDir Path root) throws IOException {
        Path home = root.resolve("home");
        Path bundle = root.resolve("Local Focus Coach.app");
        Path installer = bundle.resolve("Contents/Resources/installer/install-local-focus-coach.sh");
        Files.createDirectories(installer.getParent());
        Files.writeString(installer, "#!/bin/sh\n");

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> {
                    throw new AssertionError("Installer must not run when identity is missing");
                },
                message -> {});

        assertEquals(FirstRunBootstrap.Result.MISSING_IDENTITY, result);
    }

    @Test
    void runsInstallerWithCorrectArgumentsWhenEverythingPresent(@TempDir Path root)
            throws IOException {
        Path home = root.resolve("home");
        Path bundle = createBundleWithInstallerAndIdentity(root);
        var captured = new ArrayList<List<String>>();

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> {
                    captured.add(List.copyOf(command));
                    assertEquals(bundle, workingDirectory);
                    return 0;
                },
                message -> {});

        assertEquals(FirstRunBootstrap.Result.INSTALLED, result);
        assertEquals(1, captured.size());
        List<String> command = captured.get(0);
        assertEquals("/bin/sh", command.get(0));
        assertTrue(command.get(1).endsWith("install-local-focus-coach.sh"));
        assertEquals("--app-image", command.get(2));
        assertEquals(bundle.toString(), command.get(3));
        assertEquals("--production-identity-file", command.get(4));
        assertTrue(command.get(5).endsWith("production-extension-identity.json"));
    }

    @Test
    void returnsInstallerFailedWhenExitNonZero(@TempDir Path root) throws IOException {
        Path home = root.resolve("home");
        Path bundle = createBundleWithInstallerAndIdentity(root);
        var log = new ArrayList<String>();

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> 3,
                log::add);

        assertEquals(FirstRunBootstrap.Result.INSTALLER_FAILED, result);
        assertTrue(log.stream().anyMatch(entry -> entry.contains("status 3")));
    }

    @Test
    void doesNotThrowWhenInstallerThrows(@TempDir Path root) throws IOException {
        Path home = root.resolve("home");
        Path bundle = createBundleWithInstallerAndIdentity(root);

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> {
                    throw new IOException("boom");
                },
                message -> {});

        assertEquals(FirstRunBootstrap.Result.INSTALLER_FAILED, result);
    }

    @Test
    void doesNotThrowWhenLoggerThrows(@TempDir Path root) throws IOException {
        Path home = root.resolve("home");
        Path bundle = createBundleWithInstallerAndIdentity(root);

        var result = FirstRunBootstrap.run(
                bundle,
                home,
                (command, workingDirectory) -> {
                    throw new IOException("boom");
                },
                message -> {
                    throw new RuntimeException("logger down");
                });

        assertEquals(FirstRunBootstrap.Result.INSTALLER_FAILED, result);
    }

    @Test
    void walksUpFromLauncherPathToDotAppBundle(@TempDir Path root) throws IOException {
        Path bundle = root.resolve("Local Focus Coach.app");
        Path launcher = bundle.resolve("Contents/MacOS/Local Focus Coach");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "");

        Path found = FirstRunBootstrap.walkUpToBundle(launcher);

        assertEquals(bundle, found);
    }

    @Test
    void walkUpReturnsNullWhenNoBundleAncestor(@TempDir Path root) {
        assertNull(FirstRunBootstrap.walkUpToBundle(root.resolve("plain/directory/file")));
    }

    @Test
    void appendingLoggerWritesTimestampedLines(@TempDir Path root) throws IOException {
        Path logFile = root.resolve("logs/first-run-bootstrap.log");
        var logger = FirstRunBootstrap.appendingLogger(logFile);

        logger.accept("hello");
        logger.accept("world");

        assertTrue(Files.exists(logFile));
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("hello"));
        assertTrue(lines.get(1).endsWith("world"));
        assertTrue(lines.get(0).startsWith("["));
    }

    @Test
    void runIfEligibleIsNoOpOutsideOfAPackagedBundle() {
        Path bundle = FirstRunBootstrap.defaultAppBundle();
        boolean looksLikeRealBundle = bundle != null
                && Files.isDirectory(bundle)
                && bundle.getFileName() != null
                && bundle.getFileName().toString().endsWith(".app")
                && Files.isRegularFile(
                        bundle.resolve(
                                "Contents/Resources/installer/install-local-focus-coach.sh"));
        assertFalse(looksLikeRealBundle, "Test environment must not resolve to a real bundle");
        assertNotNull(FirstRunBootstrap.runIfEligible());
    }

    private static Path createBundleWithInstallerAndIdentity(Path root) throws IOException {
        Path bundle = root.resolve("Local Focus Coach.app");
        Path installer = bundle.resolve("Contents/Resources/installer/install-local-focus-coach.sh");
        Files.createDirectories(installer.getParent());
        Files.writeString(installer, "#!/bin/sh\nexit 0\n");
        Path identity = bundle.resolve("Contents/Resources/production-extension-identity.json");
        Files.writeString(identity, "{\"version\":1,\"channel\":\"production\"}");
        return bundle;
    }
}
