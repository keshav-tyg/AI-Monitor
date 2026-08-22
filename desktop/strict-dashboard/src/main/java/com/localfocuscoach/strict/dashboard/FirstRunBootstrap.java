package com.localfocuscoach.strict.dashboard;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class FirstRunBootstrap {
    static final String PROD_MANIFEST = "com.localfocuscoach.strict_mode.json";
    static final String DEV_MANIFEST = "com.localfocuscoach.strict_mode_dev.json";

    public interface InstallerRunner {
        int run(List<String> command, Path workingDirectory) throws IOException, InterruptedException;
    }

    public enum Result {
        ALREADY_REGISTERED,
        NO_BUNDLE_PATH,
        MISSING_INSTALLER,
        MISSING_IDENTITY,
        INSTALLER_FAILED,
        INSTALLED
    }

    private FirstRunBootstrap() {}

    public static Result runIfEligible() {
        Path home = Path.of(System.getProperty("user.home"));
        return run(
                defaultAppBundle(),
                home,
                FirstRunBootstrap::runInstallerProcess,
                appendingLogger(defaultLogFile(home)));
    }

    static Result run(
            Path appBundle,
            Path homeDirectory,
            InstallerRunner runner,
            Consumer<String> logger) {
        Objects.requireNonNull(homeDirectory, "homeDirectory");
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(logger, "logger");
        try {
            Path nativeHosts = homeDirectory.resolve(
                    "Library/Application Support/Google/Chrome/NativeMessagingHosts");
            Path prod = nativeHosts.resolve(PROD_MANIFEST);
            Path dev = nativeHosts.resolve(DEV_MANIFEST);
            if (isRegularFileNoFollow(prod) || isRegularFileNoFollow(dev)) {
                logger.accept("Bootstrap skipped: native host manifest already registered");
                return Result.ALREADY_REGISTERED;
            }
            if (appBundle == null
                    || !Files.isDirectory(appBundle)
                    || appBundle.getFileName() == null
                    || !appBundle.getFileName().toString().endsWith(".app")) {
                logger.accept(
                        "Bootstrap skipped: not running from a packaged .app bundle (path="
                                + appBundle
                                + ")");
                return Result.NO_BUNDLE_PATH;
            }
            Path installer = appBundle.resolve(
                    "Contents/Resources/installer/install-local-focus-coach.sh");
            if (!isRegularFileNoFollow(installer)) {
                logger.accept("Bootstrap skipped: installer script missing at " + installer);
                return Result.MISSING_INSTALLER;
            }
            Path identity = appBundle.resolve(
                    "Contents/Resources/production-extension-identity.json");
            if (!isRegularFileNoFollow(identity)) {
                logger.accept("Bootstrap skipped: production identity file missing at " + identity);
                return Result.MISSING_IDENTITY;
            }
            List<String> command = List.of(
                    "/bin/sh",
                    installer.toString(),
                    "--app-image",
                    appBundle.toString(),
                    "--production-identity-file",
                    identity.toString());
            logger.accept("Running native-host installer: " + String.join(" ", command));
            int exit = runner.run(command, appBundle);
            if (exit == 0) {
                logger.accept("Native host installer completed successfully");
                return Result.INSTALLED;
            }
            logger.accept("Native host installer exited with status " + exit);
            return Result.INSTALLER_FAILED;
        } catch (Throwable throwable) {
            safeLog(logger, "Bootstrap threw: "
                    + throwable.getClass().getSimpleName()
                    + ": "
                    + throwable.getMessage());
            return Result.INSTALLER_FAILED;
        }
    }

    static Path defaultAppBundle() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path bundle = walkUpToBundle(Path.of(appPath));
            if (bundle != null) {
                return bundle;
            }
        }
        try {
            var codeSource = FirstRunBootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path location = Path.of(codeSource.getLocation().toURI());
                return walkUpToBundle(location);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static Path walkUpToBundle(Path start) {
        Path current = start;
        while (current != null) {
            Path name = current.getFileName();
            if (name != null && name.toString().endsWith(".app")) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    static Path defaultLogFile(Path home) {
        return home.resolve("Library/Application Support/Local Focus Coach/logs/first-run-bootstrap.log");
    }

    static Consumer<String> appendingLogger(Path logFile) {
        return message -> {
            try {
                Path parent = logFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String line = "[" + Instant.now() + "] " + message + System.lineSeparator();
                Files.writeString(
                        logFile,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        };
    }

    private static int runInstallerProcess(List<String> command, Path workingDirectory)
            throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        try (var stream = process.getInputStream()) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return process.waitFor();
    }

    private static boolean isRegularFileNoFollow(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void safeLog(Consumer<String> logger, String message) {
        try {
            logger.accept(message);
        } catch (Throwable ignored) {
        }
    }
}
