package com.ascendersmc.installer;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.service.UpdateService;
import com.ascendersmc.installer.ui.InstallerFrame;
import com.ascendersmc.installer.ui.ModernDialog;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.ProcessRegistry;

import javax.swing.*;
import java.awt.*;

public final class App {
    public static final String VERSION = "0.8.4";
    private App() {}

    public static void main(String[] args) {
        try {
            if (UpdateService.handleApplyMode(args)) return;

            if (Runtime.version().feature() < 21) {
                JOptionPane.showMessageDialog(null,
                        "ASCENDERSMC UNIVERSAL INSTALLER requiere Java 21 o superior.",
                        "Java incompatible",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            InstallerConfig config = InstallerConfig.load();
            InstallerLogger.init(config.logsDir(), config.debugEnabled());
            InstallerLogger.debug("BOOT", "version=" + VERSION + " | java=" + System.getProperty("java.version")
                    + " | os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                    + " | arch=" + System.getProperty("os.arch"));
            InstallerLogger.debug("BOOT", "baseDir=" + config.baseDir() + " | cacheDir=" + config.cacheDir()
                    + " | logsDir=" + config.logsDir());

            Runtime.getRuntime().addShutdownHook(new Thread(ProcessRegistry::shutdownAll, "ascendersmc-installer-shutdown"));
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                InstallerLogger.error("UI", throwable.getMessage(), throwable);
                SwingUtilities.invokeLater(() -> ModernDialog.show(null,
                        "Error inesperado",
                        throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage()),
                        ModernDialog.Type.ERROR));
            });

            UIManager.put("OptionPane.background", new Color(22, 20, 31));
            UIManager.put("Panel.background", new Color(22, 20, 31));
            UIManager.put("OptionPane.messageForeground", Color.WHITE);

            SwingUtilities.invokeLater(() -> {
                InstallerFrame frame = new InstallerFrame(config);
                frame.setVisible(true);
                startBackgroundUpdateCheck(config, frame);
            });
        } catch (Throwable throwable) {
            showStartupError(throwable);
        }
    }

    /**
     * La ventana se muestra antes de tocar la red. De esta forma un GitHub lento,
     * sin conexión o bloqueado no hace que un doble clic parezca no abrir nada.
     */
    private static void startBackgroundUpdateCheck(InstallerConfig config, InstallerFrame frame) {
        Thread updater = new Thread(() -> {
            try {
                // Pequeño margen para que Swing termine de pintar la ventana.
                Thread.sleep(800L);
                if (UpdateService.checkAndUpdate(config, VERSION, () -> frame == null || !frame.isInstallationInProgress())) {
                    InstallerLogger.info("UPDATE", "Relevo entregado al JAR nuevo; cerrando versión " + VERSION);
                    System.exit(0);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable throwable) {
                InstallerLogger.warn("UPDATE", "Comprobación en segundo plano falló: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }, "ascendersmc-update-check");
        updater.setDaemon(true);
        updater.start();
    }

    private static void showStartupError(Throwable throwable) {
        try {
            throwable.printStackTrace(System.err);
        } catch (Throwable ignored) { }

        String message = throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
        try {
            JOptionPane.showMessageDialog(null,
                    "No se pudo iniciar ASCENDERSMC UNIVERSAL INSTALLER.\n\n" + message,
                    "ASCENDERSMC UNIVERSAL INSTALLER",
                    JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) { }
    }
}
