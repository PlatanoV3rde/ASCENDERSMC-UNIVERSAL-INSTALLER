package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Pruebas aisladas sin instalación de SKLauncher/Windows. */
public final class SkLauncher4CompatibilityTest {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("ascendersmc-skl4-smoke-");
        WindowsLauncherDiscovery.InstalledApp sk3 = new WindowsLauncherDiscovery.InstalledApp(
                "SKlauncher 3.2.18", "C:\\SKlauncher3", "", "", "", "3.2.18");
        WindowsLauncherDiscovery.InstalledApp sk4 = new WindowsLauncherDiscovery.InstalledApp(
                "SKlauncher", "C:\\Users\\Test\\AppData\\Local\\Programs\\SKlauncher", "", "", "", "4.0.54");
        WindowsLauncherDiscovery.Shortcut shortcut3 = new WindowsLauncherDiscovery.Shortcut(sandbox.resolve("SKlauncher.lnk"), "SKlauncher");
        WindowsLauncherDiscovery.Shortcut shortcut4 = new WindowsLauncherDiscovery.Shortcut(sandbox.resolve("SKlauncher 4.0.lnk"), "SKlauncher 4.0");
        check(!SkLauncherRegistrar.isVersion4(shortcut3, sk3, null), "El perfil 3.2 no debe cambiar a 4.0");
        check(SkLauncherRegistrar.isVersion4(shortcut4, sk4, null), "No reconoce 4.0");
        check(SkLauncherRegistrar.isVersion4(shortcut3, sk4, null), "No reconoce la versión de registro 4.x");
        check(SkLauncherRegistrar.isVersion4(shortcut4, sk3, null), "No prioriza 4.x cuando coexisten");

        InstallerConfig.ProfileConfig config = new InstallerConfig.ProfileConfig(
                InstallerProfile.COBBLEWORLD, "", "", "", "1.21.1", "21.1.248", "", "");
        Path staging = sandbox.resolve("staging");
        Files.createDirectories(staging.resolve("mods"));
        Files.createDirectories(staging.resolve("resourcepacks"));
        Files.writeString(staging.resolve("mods/test.jar"), "mod-test", StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("resourcepacks/pack.zip"), "texture", StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("icon.png"), "old", StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("ICON.PNG"), "also old", StandardCharsets.UTF_8);

        Path instances = sandbox.resolve(".sklauncher/instances");
        Files.createDirectories(instances.resolve("orphan"));
        check(SkLauncherRegistrar.findImportedInstance(instances, InstallerProfile.COBBLEWORLD) == null,
                "Una carpeta existente no debe reconocerse como perfil importado");
        Path pack = sandbox.resolve("ASCENDERSMC-COBBLEWORLD-SKLAUNCHER4.mrpack");
        ModrinthRegistrar.buildMrpack(config, staging, pack,
                SkLauncherRegistrar.IMPORT_MARKER,
                SkLauncherRegistrar.markerContent(InstallerProfile.COBBLEWORLD));
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            check(zip.getEntry("modrinth.index.json") != null, "Falta descriptor mrpack");
            check(zip.getEntry("overrides/" + SkLauncherRegistrar.IMPORT_MARKER) != null,
                    "Falta el marcador de instancia importada");
            check(zip.getEntry("overrides/mods/test.jar") != null, "Faltan mods");
            check(zip.getEntry("overrides/resourcepacks/pack.zip") != null, "Faltan resourcepacks");
            check(zip.getEntry("overrides/" + ModrinthRegistrar.IMPORT_MARKER) == null,
                    "El marcador de Modrinth no debe entrar al paquete SKLauncher 4");
            Set<String> names = new HashSet<>();
            var list = zip.entries();
            while (list.hasMoreElements()) {
                String name = list.nextElement().getName().toLowerCase(Locale.ROOT);
                check(names.add(name), "Duplicado en paquete: " + name);
            }
            String idx = new String(zip.getInputStream(zip.getEntry("modrinth.index.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            check(idx.contains("\"minecraft\": \"1.21.1\""), "Minecraft no fijado");
            check(idx.contains("\"neoforge\": \"21.1.248\""), "NeoForge no fijado");
        }
        check(!Files.exists(staging.resolve(SkLauncherRegistrar.IMPORT_MARKER)),
                "Nunca escribir el marcador en staging");
        Path imported = instances.resolve("ASCENDERSMC COBBLEWORLD (1)");
        Files.createDirectories(imported);
        Files.writeString(imported.resolve(SkLauncherRegistrar.IMPORT_MARKER),
                SkLauncherRegistrar.markerContent(InstallerProfile.COBBLEWORLD));
        check(SkLauncherRegistrar.findImportedInstance(instances, InstallerProfile.COBBLEWORLD) == null,
                "Un marcador huérfano no es registro nativo");
        SkLauncher4Registry.register(instances, InstallerProfile.COBBLEWORLD,
                "1.21.1", "21.1.248", imported);
        check(imported.equals(SkLauncherRegistrar.findImportedInstance(instances, InstallerProfile.COBBLEWORLD)),
                "No encontró la instancia registrada en instances.json");
        check(SkLauncherRegistrar.findImportedInstance(instances, InstallerProfile.PIXELMON) == null,
                "Se mezclaron las instancias de Pixelmon y Cobbleworld");

        InstallTarget v3 = new InstallTarget(LauncherType.SKLAUNCHER, "SKLauncher", true,
                sandbox.resolve("old"), sandbox.resolve(".minecraft"), sandbox.resolve("old"), null, true, "");
        InstallTarget v4 = new InstallTarget(LauncherType.SKLAUNCHER, "SKLauncher", true,
                staging, sandbox.resolve(".sklauncher"), instances.resolve("ASCENDERSMC"), null, true, "");
        check(!SkLauncherRegistrar.isVersion4(v3) && SkLauncherRegistrar.isVersion4(v4),
                "Distinción de ruta launcherRoot incorrecta");
        System.out.println("PASS: SKLauncher 3.x/4.x, MRPACK, iconos, Minecraft/NeoForge, actualización y aislamiento de perfiles");
    }
}
