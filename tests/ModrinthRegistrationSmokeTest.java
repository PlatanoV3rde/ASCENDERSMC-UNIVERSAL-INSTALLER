package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallerProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.ZipFile;

/** Sin Modrinth real: prueba regresiones del registro vs carpeta huérfana. */
public final class ModrinthRegistrationSmokeTest {
    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("ascendersmc-modrinth-test-");
        Path nativeRoot = sandbox.resolve("ModrinthApp");
        Path orphan = nativeRoot.resolve("profiles/ASCENDERSMC-COBBLEWORLD");
        Files.createDirectories(orphan.resolve("mods"));
        Files.writeString(orphan.resolve("mods/existing.jar"), "test", StandardCharsets.UTF_8);
        Files.writeString(orphan.resolve("ASCENDERSMC-PROFILE.txt"), "old", StandardCharsets.UTF_8);
        if (ModrinthRegistrar.findImportedInstance(nativeRoot, InstallerProfile.COBBLEWORLD) != null)
            throw new AssertionError("Una carpeta huérfana NO es una instancia importada");

        InstallerConfig.ProfileConfig config = new InstallerConfig.ProfileConfig(
                InstallerProfile.COBBLEWORLD, "", "", "", "1.21.1", "21.1.248", "", "");
        Path pack = sandbox.resolve("cobbleworld.mrpack");
        ModrinthRegistrar.buildMrpack(config, orphan, pack);
        if (Files.exists(orphan.resolve(ModrinthRegistrar.IMPORT_MARKER)))
            throw new AssertionError("El marcador no puede escribirse directamente en la carpeta huérfana");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            if (zip.getEntry("modrinth.index.json") == null) throw new AssertionError("Falta modrinth.index.json");
            if (zip.getEntry("overrides/" + ModrinthRegistrar.IMPORT_MARKER) == null) throw new AssertionError("Falta marcador");
            if (zip.getEntry("overrides/mods/existing.jar") == null) throw new AssertionError("No conserva mods");
            if (zip.getEntry("overrides/ASCENDERSMC-PROFILE.txt") != null) throw new AssertionError("Incluyó info del staging");
            String idx = new String(zip.getInputStream(zip.getEntry("modrinth.index.json")).readAllBytes(), StandardCharsets.UTF_8);
            if (!idx.contains("\"neoforge\": \"21.1.248\"")) throw new AssertionError("NeoForge incorrecto: " + idx);
        }

        Path imported = nativeRoot.resolve("profiles/ASCENDERSMC COBBLEWORLD (1)");
        Files.createDirectories(imported);
        Files.writeString(imported.resolve(ModrinthRegistrar.IMPORT_MARKER),
                "ASCENDERSMC-MODRINTH:COBBLEWORLD\n", StandardCharsets.UTF_8);
        if (!imported.equals(ModrinthRegistrar.findImportedInstance(nativeRoot, InstallerProfile.COBBLEWORLD)))
            throw new AssertionError("No localizó el perfil importado con nombre modificado");
        if (ModrinthRegistrar.findImportedInstance(nativeRoot, InstallerProfile.PIXELMON) != null)
            throw new AssertionError("Se cruzaron los perfiles Cobbleworld/Pixelmon");
        System.out.println("OK: carpetas huérfanas, paquete MRPACK, NeoForge y búsqueda de instancias importadas");
    }
}
