package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.util.InstallerLogger;

import java.io.InputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Intenta completar la importacion a traves de la UI publica de SKLauncher 4.x.
 * No edita bases de datos, el listado interno ni directorios nativos sin confirmar.
 * La unica condicion de exito es ver el marcador importado en instances/.
 * La interfaz Electron puede cambiar en nuevas betas: si falla, vuelve a importacion pendiente.
 */
public final class SkLauncher4AutoImporter {
    private SkLauncher4AutoImporter() { }

    public static boolean tryImport(InstallerConfig config,
                                    InstallerProfile profile,
                                    InstallTarget target,
                                    Path mrpack) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            InstallerLogger.warn("SK4AUTO", "UI automation disponible solo en Windows");
            return false;
        }
        if (target.launcherExecutable() == null || !Files.isRegularFile(target.launcherExecutable())) {
            InstallerLogger.warn("SK4AUTO", "No se ha localizado el ejecutable real de SKLauncher 4.x");
            return false;
        }
        if (!Files.isRegularFile(mrpack)) {
            InstallerLogger.warn("SK4AUTO", "No hay paquete para importar: " + mrpack);
            return false;
        }
        Path script = config.stateDir(profile).resolve("sklauncher4").resolve("sklauncher4-import.ps1");
        try {
            Files.createDirectories(script.getParent());
            try (InputStream in = SkLauncher4AutoImporter.class.getResourceAsStream("/assets/automation/sklauncher4-import.ps1")) {
                if (in == null) throw new IllegalStateException("Falta el recurso de automatizacion de SKLauncher 4");
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            var builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString(),
                    "-Launcher", target.launcherExecutable().toString(),
                    "-Modpack", mrpack.toString(),
                    "-Instances", config.sklauncher4InstancesDir().toString(),
                    "-Marker", SkLauncherRegistrar.IMPORT_MARKER,
                    "-MarkerValue", SkLauncherRegistrar.markerContent(profile),
                    "-TimeoutSeconds", "105");
            builder.redirectErrorStream(true);
            InstallerLogger.info("SK4AUTO", "Iniciando importacion automatica usando la interfaz de SKLauncher 4.x");
            Process process = builder.start();
            // Un lector daemon evita bloquear el proceso si PowerShell informa errores.
            Thread reader = new Thread(() -> {
                try (var lines = process.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (!line.isBlank()) InstallerLogger.debug("SK4AUTO", line);
                    }
                } catch (Exception ex) {
                    InstallerLogger.debug("SK4AUTO", "log-reader: " + ex.getMessage());
                }
            }, "ascendersmc-sklauncher4-uia");
            reader.setDaemon(true);
            reader.start();
            boolean finished;
            try { finished = process.waitFor(120, TimeUnit.SECONDS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return false;
            }
            if (!finished) {
                process.destroyForcibly();
                InstallerLogger.warn("SK4AUTO", "Agotado tiempo de automatizacion; no se asume exito");
                return false;
            }
            Path imported = SkLauncherRegistrar.findImportedInstance(config.sklauncher4InstancesDir(), profile);
            if (imported != null) {
                InstallerLogger.info("SK4AUTO", "Instancia importada y verificada en " + imported);
                return true;
            }
            InstallerLogger.warn("SK4AUTO", "PowerShell exit=" + process.exitValue()
                    + "; la biblioteca no muestra un marcador de importacion verificable");
        } catch (Exception e) {
            InstallerLogger.warn("SK4AUTO", "No se completo la automatizacion: " + e.getMessage());
        }
        return false;
    }
}
