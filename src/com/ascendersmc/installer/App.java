package com.ascendersmc.installer;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.ui.InstallerFrame;
import com.ascendersmc.installer.service.UpdateService;
import com.ascendersmc.installer.ui.ModernDialog;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.ProcessRegistry;

import javax.swing.*;
import java.awt.*;

public final class App {
    public static final String VERSION = "0.8.0";
    private App() {}

    public static void main(String[] args) throws Exception {
        if (UpdateService.handleApplyMode(args)) return;

        if (Runtime.version().feature() < 21) {
            JOptionPane.showMessageDialog(null,
                    "ASCENDERSMC Universal Installer requiere Java 21 o superior.",
                    "Java incompatible",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        InstallerConfig config = InstallerConfig.load();
        InstallerLogger.init(config.logsDir(), config.debugEnabled());
        InstallerLogger.debug("BOOT", "version=" + VERSION + " | java=" + System.getProperty("java.version") + " | os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " | arch=" + System.getProperty("os.arch"));
        InstallerLogger.debug("BOOT", "baseDir=" + config.baseDir() + " | cacheDir=" + config.cacheDir() + " | logsDir=" + config.logsDir());

        // La actualización se comprueba antes de construir la UI. Si se entrega
        // el relevo al JAR nuevo, este proceso termina y el helper lo reemplaza.
        if (UpdateService.checkAndUpdate(config, VERSION)) return;

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

        SwingUtilities.invokeLater(() -> new InstallerFrame(config).setVisible(true));
    }
}
