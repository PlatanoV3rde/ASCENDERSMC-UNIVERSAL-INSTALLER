package com.ascendersmc.installer.service;

import com.ascendersmc.installer.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Pure filesystem tests: no network, GUI or actual launcher restart. */
public final class InstallerUpdateFilenameTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("ascendersmc-update-test-");
        try {
            testVersionedOldJar(temp.resolve("versioned"));
            testWindowsDuplicateName(temp.resolve("windows-download"));
            testCanonicalInPlace(temp.resolve("same-name"));
            testOverwriteExistingCanonical(temp.resolve("collision"));
            testRollbackWhenLaunchFails(temp.resolve("rollback"));
            testRollbackWhenLaunchFailsWithoutCanonical(temp.resolve("rollback-new"));
            testRejectIncorrectHash(temp.resolve("bad-checksum"));
            System.out.println("PASS: 7 pruebas de nombre fijo, sustitución y recuperación");
        } finally { cleanup(temp); }
    }

    private static void testVersionedOldJar(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path old = file(dir, "ASCENDERSMC-UNIVERSAL-INSTALLER-v0.8.5.jar", "old-0.8.5");
        Path downloaded = file(dir.resolve("updates"), "update.jar", "new-0.8.6");
        List<Path> started = new ArrayList<>();
        Path installed = UpdateService.applyDownloadedJar(downloaded, old, Hashing.sha256(downloaded), started::add);
        require(installed.equals(dir.resolve(UpdateService.CANONICAL_JAR_NAME)), "debe instalar el nombre canónico");
        require(started.equals(List.of(installed)), "debe relanzar el JAR canónico");
        require("new-0.8.6".equals(Files.readString(installed)), "nuevo contenido");
        require(Files.notExists(old), "debe retirar el JAR con versión anterior");
        require(Files.exists(downloaded), "caché intacto");
    }

    private static void testWindowsDuplicateName(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path old = file(dir, "ASCENDERSMC-UNIVERSAL-INSTALLER (4).jar", "old");
        Path downloaded = file(dir.resolve("updates"), "update.jar", "updated");
        Path result = UpdateService.applyDownloadedJar(downloaded, old, null, ignored -> {});
        require(result.getFileName().toString().equals("ASCENDERSMC-UNIVERSAL-INSTALLER.jar"), "nombre fijo Windows");
        require(Files.notExists(old), "debe limpiar antiguo duplicado");
    }

    private static void testCanonicalInPlace(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path old = file(dir, UpdateService.CANONICAL_JAR_NAME, "old");
        Path downloaded = file(dir.resolve("updates"), "update.jar", "new");
        UpdateService.applyDownloadedJar(downloaded, old, Hashing.sha256(downloaded), ignored -> {});
        require("new".equals(Files.readString(old)), "reemplazo in-place");
        try (var stream = Files.list(dir)) {
            require(stream.filter(Files::isRegularFile).count() == 1, "sin temporales visibles");
        }
    }

    private static void testOverwriteExistingCanonical(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path old = file(dir, "ASCENDERSMC-UNIVERSAL-INSTALLER-0.8.5.jar", "old-versioned");
        Path canonical = file(dir, UpdateService.CANONICAL_JAR_NAME, "old-canonical");
        Path downloaded = file(dir.resolve("updates"), "update.jar", "latest");
        UpdateService.applyDownloadedJar(downloaded, old, Hashing.sha256(downloaded), ignored -> {});
        require("latest".equals(Files.readString(canonical)), "sustituye canonical viejo");
        require(Files.notExists(old), "elimina solo original antiguo");
    }

    private static void testRollbackWhenLaunchFails(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path old = file(dir, "ASCENDERSMC-UNIVERSAL-INSTALLER-0.8.5.jar", "old-versioned");
        Path canonical = file(dir, UpdateService.CANONICAL_JAR_NAME, "old-canonical");
        Path downloaded = file(dir.resolve("updates"), "update.jar", "new");
        try {
            UpdateService.applyDownloadedJar(downloaded, old, Hashing.sha256(downloaded), jar -> { throw new IOException("launch failed"); });
            throw new AssertionError("debió fallar");
        } catch (IOException e) { require("launch failed".equals(e.getMessage()), "causa del fallo preservada"); }
        require("old-canonical".equals(Files.readString(canonical)), "rollback recupera canónico viejo");
        require("old-versioned".equals(Files.readString(old)), "rollback no borra original");
    }

    private static void testRollbackWhenLaunchFailsWithoutCanonical(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path old = file(dir, "ASCENDERSMC-UNIVERSAL-INSTALLER-v0.8.5.jar", "old");
        Path downloaded = file(dir.resolve("updates"), "update.jar", "new");
        try {
            UpdateService.applyDownloadedJar(downloaded, old, null, jar -> { throw new IOException("launch failed"); });
            throw new AssertionError("debió fallar");
        } catch (IOException expected) { }
        require(Files.notExists(dir.resolve(UpdateService.CANONICAL_JAR_NAME)), "sin canónico nuevo si falla arranque");
        require(Files.exists(old), "conserva original tras fallo");
    }

    private static void testRejectIncorrectHash(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path old = file(dir, "ASCENDERSMC-UNIVERSAL-INSTALLER-0.8.5.jar", "old");
        Path downloaded = file(dir.resolve("updates"), "update.jar", "new");
        try {
            UpdateService.applyDownloadedJar(downloaded, old, "0".repeat(64), jar -> {});
            throw new AssertionError("debió rechazar hash");
        } catch (SecurityException expected) { }
        require(Files.exists(old), "hash inválido no altera original");
        require(Files.notExists(dir.resolve(UpdateService.CANONICAL_JAR_NAME)), "hash inválido no crea resultado");
    }

    private static Path file(Path dir, String name, String contents) throws IOException {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, contents, StandardCharsets.UTF_8);
        return path;
    }
    private static void require(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    private static void cleanup(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted((a,b) -> b.getNameCount()-a.getNameCount()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
