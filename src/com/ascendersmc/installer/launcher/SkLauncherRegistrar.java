package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.util.InstallerLogger;

import java.awt.Desktop;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Compatibilidad de SKLauncher 3.2 y SKLauncher 4.x.
 *
 * SK 3.x usa launcher_profiles.json (OfficialLauncherRegistrar).
 * SK 4.x tiene su propio almacén y un importador de modpacks. Crear carpetas
 * debajo de instances/ NO registra la instancia. No se altera su base de datos.
 * Se intenta automatizar la importación mediante su interfaz nativa en Windows.
 * Si no es comprobable, se informa honestamente que la importación sigue pendiente.
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

    public static LauncherRegistration register(InstallerConfig config,
                                                InstallerConfig.ProfileConfig profile,
                                                InstallTarget target) throws Exception {
        if (!isVersion4(target)) {
            OfficialLauncherRegistrar.register(profile, target);
            return LauncherRegistration.ready("Perfil SKLauncher 3.x registrado");
        }

        Path imported = findImportedInstance(config.sklauncher4InstancesDir(), profile.profile());
        if (imported != null && imported.equals(target.minecraftDir())) {
            InstallerLogger.info("SKLAUNCHER4", "Instancia importada; actualizado su contenido: " + imported);
            return LauncherRegistration.ready("Instancia SKLauncher 4.x actualizada");
        }

        Path dir = config.stateDir(profile.profile()).resolve("sklauncher4");
        Files.createDirectories(dir);
        Path mrpack = dir.resolve("ASCENDERSMC-" + profile.profile().displayName() + "-SKLAUNCHER4.mrpack");
        ModrinthRegistrar.buildMrpack(profile, target.minecraftDir(), mrpack,
                IMPORT_MARKER, markerContent(profile.profile()));
        InstallerLogger.info("SKLAUNCHER4", "Paquete .mrpack generado | " + mrpack);
        // Registro por la propia interfaz de SKLauncher: no inventar esquemas de
        // datos internos, ni asumir que solo crear un directorio lo registra.
        // La verificacion ocurre despues de que el launcher haya importado el pack.
        if (SkLauncher4AutoImporter.tryImport(config, profile.profile(), target, mrpack)) {
            return LauncherRegistration.ready("Instancia SKLauncher 4.x importada y verificada automaticamente");
        }
        InstallerLogger.warn("SKLAUNCHER4", "La importacion automatica no se pudo confirmar; paquete disponible como alternativa");

        // No asumir asociación de .mrpack: Windows suele enviarlo a Modrinth
        // cuando ambos launchers están instalados. Abrir solo la carpeta.
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(mrpack.getParent().toFile());
            }
        } catch (Exception e) {
            InstallerLogger.debug("SKLAUNCHER4", "No se pudo abrir carpeta de importación: " + e.getMessage());
        }
        String instructions = "Abre SKLauncher 4.x, entra en Biblioteca > Importar desde archivo "
                + "(o arrastra el paquete al launcher) y selecciona este .mrpack. "
                + "Termina la importación y comprueba que ASCENDERSMC " + profile.profile().displayName()
                + " aparezca como instancia con Minecraft " + profile.minecraftVersion()
                + " y NeoForge " + profile.neoForgeVersion() + ". "
                + "No borres la carpeta anterior. Cuando importes el perfil, las futuras actualizaciones "
                + "del Installer usarán la ubicación de esa instancia, siempre que permanezca en la carpeta "
                + "de instancias configurada en el instalador.";
        return new LauncherRegistration(true, mrpack, "Importación pendiente en SKLauncher 4.x", instructions);
    }

    static String markerContent(InstallerProfile profile) {
        return "ASCENDERSMC-SKLAUNCHER4:" + profile.name();
    }

    public static Path findImportedInstance(Path instancesDir, InstallerProfile profile) {
        if (instancesDir == null || profile == null || !Files.isDirectory(instancesDir)) return null;
        List<Path> found = new ArrayList<>();
        try (var dirs = Files.list(instancesDir)) {
            for (Path candidate : dirs.filter(Files::isDirectory).toList()) {
                Path marker = candidate.resolve(IMPORT_MARKER);
                if (!Files.isRegularFile(marker)) continue;
                try {
                    if (Files.readString(marker, StandardCharsets.UTF_8).trim().equals(markerContent(profile))) {
                        found.add(candidate.toAbsolutePath().normalize());
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ex) {
            InstallerLogger.warn("SKLAUNCHER4", "No se pudieron buscar instancias importadas: " + ex.getMessage());
        }
        if (found.isEmpty()) return null;
        found.sort(Comparator.comparingLong(SkLauncherRegistrar::modified).reversed());
        if (found.size() > 1) InstallerLogger.warn("SKLAUNCHER4", "Varias instancias ASCENDERSMC; usando la más reciente: " + found);
        return found.get(0);
    }

    private static long modified(Path root) {
        try { return Files.getLastModifiedTime(root.resolve(IMPORT_MARKER)).toMillis(); }
        catch (Exception ex) { return 0; }
    }
}
