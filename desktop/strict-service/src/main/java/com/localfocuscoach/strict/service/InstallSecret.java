package com.localfocuscoach.strict.service;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

public final class InstallSecret {
    private static final String FILE_NAME = "install-secret";

    private InstallSecret() {}

    public static String loadOrCreateDefault() {
        return loadOrCreate(defaultAppSupportDirectory());
    }

    public static String loadOrCreate(Path appSupportDirectory) {
        try {
            Files.createDirectories(appSupportDirectory);
            Files.setPosixFilePermissions(
                    appSupportDirectory, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
            var path = appSupportDirectory.resolve(FILE_NAME);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                createSecret(path);
            }
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Install secret must be a regular file");
            }
            Files.setPosixFilePermissions(path, Set.of(OWNER_READ, OWNER_WRITE));
            var secret = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (secret.isEmpty()) {
                throw new IllegalStateException("Install secret is empty");
            }
            return secret;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load install secret", exception);
        }
    }

    public static Path defaultAppSupportDirectory() {
        return Path.of(
                System.getProperty("user.home"),
                "Library",
                "Application Support",
                "Local Focus Coach");
    }

    private static void createSecret(Path destination) throws IOException {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        var secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var permissions = PosixFilePermissions.asFileAttribute(
                Set.of(OWNER_READ, OWNER_WRITE));
        var temporary = Files.createTempFile(
                destination.getParent(), ".install-secret-", ".tmp", permissions);
        try {
            Files.writeString(temporary, secret + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination);
            } catch (FileAlreadyExistsException exception) {
                Files.deleteIfExists(temporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
