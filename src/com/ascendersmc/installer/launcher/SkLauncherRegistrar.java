package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.util.InstallerLogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

/**
 * Compatibilidad de SKLauncher 3.2 y SKLauncher 4.x.
 *
 * SK 3.x usa launcher_profiles.json (OfficialLauncherRegistrar).
 * SK 4.x registra instancias en .sklauncher/instances.json (confirmado con datos
 * reales de SKLauncher 4.0.54). La creación se hace mediante escritura atómica
 * y copia de seguridad, sin automatización de pantalla ni importación .mrpack.
 * No registrar mientras SKLauncher está ejecutándose.
 */
public final class SkLauncherRegistrar {
    public static final String IMPORT_MARKER = "ASCENDERSMC-SKLAUNCHER4-IMPORT.txt";
    private SkLauncherRegistrar() {}

    /** Solo se considera 4.x si hay evidencia de Windows y no un directorio residual. */
    static boolean isVersion4(WindowsLauncherDiscovery.Shortcut shortcut,
                              WindowsLauncherDiscovery.InstalledApp installedApp,
                              Path executable) {
        String shortcutName = shortcut == null ? "" : shortcut.name().toLowerCase(Locale.ROOT);
        String appName = installedApp == null ? "" : installedApp.displayName().toLowerCase(Locale.ROOT);
        String appVersion = installedApp == null ? "" : installedApp.displayVersion().toLowerCase(Locale.ROOT);
        if (looksV4(shortcutName) || looksV4(appName) || appVersion.matches("4(?:\\..*)?")) return true;
        if (looksV3(shortcutName) || looksV3(appName) || appVersion.matches("3(?:\\..*)?")) return false;
        // 4.x es una app nativa sin Java. El 3.2 instalado tiene versión 3.x
        // en el registro; una app sin versión con EXE en Programs es 4.x.
        String install = installedApp == null ? "" : installedApp.installLocation().replace('/', '\\').toLowerCase(Locale.ROOT);
        String exe = executable == null ? "" : executable.toString().replace('/', '\\').toLowerCase(Locale.ROOT);
        return (install.contains("\\programs\\sklauncher") || exe.contains("\\programs\\sklauncher"))
                && (installedApp != null);
    }

    private static boolean looksV4(String value) {
        return value.contains("sklauncher 4") || value.contains("sklauncher4")
                || value.contains("sklauncher next") || value.contains("sklauncher beta");
    }
    private static boolean looksV3(String value) {
        return value.contains("sklauncher 3") || value.contains("sklauncher3");
    }

    public static boolean isVersion4(InstallTarget target) {
        return target.launcherRoot() != null && target.launcherRoot().getFileName() != null
                && target.launcherRoot().getFileName().toString().equalsIgnoreCase(".sklauncher");
    }

    public static void preflight(InstallTarget target) {
        if (isVersion4(target)) SkLauncher4Registry.assertLauncherClosed(target);
    }

    public static LauncherRegistration register(InstallerConfig config,
                                                InstallerConfig.ProfileConfig profile,
                                                InstallTarget target) throws Exception {
        if (!isVersion4(target)) {
            OfficialLauncherRegistrar.register(profile, target);
            return LauncherRegistration.ready("Perfil SKLauncher 3.x registrado");
        }
        SkLauncher4Registry.assertLauncherClosed(target);
        SkLauncher4Registry.register(config.sklauncher4InstancesDir(), profile.profile(),
                profile.minecraftVersion(), profile.neoForgeVersion(), target.minecraftDir());
        Files.writeString(target.minecraftDir().resolve(IMPORT_MARKER),
                markerContent(profile.profile()), StandardCharsets.UTF_8);
        String versionId = profile.minecraftVersion() + "-neoforge-" + profile.neoForgeVersion();
        Path nativeVersion = config.sklauncher4InstancesDir().getParent()
                .resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(nativeVersion)) {
            InstallerLogger.warn("SKLAUNCHER4", "Instancia registrada; aún no aparece el descriptor "
                    + nativeVersion + ". SKLauncher podría descargar el runtime en el primer inicio.");
        }
        return LauncherRegistration.ready("Instancia SKLauncher 4.x registrada de forma nativa");
    }

    static String markerContent(InstallerProfile profile) {
        return "ASCENDERSMC-SKLAUNCHER4:" + profile.name();
    }

    public static Path findImportedInstance(Path instancesDir, InstallerProfile profile) {
        return SkLauncher4Registry.findRegisteredInstance(instancesDir, profile);
    }
}
