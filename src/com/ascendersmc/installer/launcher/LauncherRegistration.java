package com.ascendersmc.installer.launcher;

import java.nio.file.Path;

/**
 * Resultado de registrar/preparar un perfil en un launcher.
 *
 * Algunos launchers exponen un formato de importación estable pero no una API
 * pública para crear el perfil silenciosamente. En esos casos el Installer
 * prepara el paquete oficial y la UI muestra el paso de importación pendiente.
 */
public record LauncherRegistration(
        boolean importRequired,
        Path importPackage,
        String status,
        String instructions
) {
    public LauncherRegistration {
        if (importPackage != null) importPackage = importPackage.toAbsolutePath().normalize();
        status = status == null ? "Perfil preparado" : status;
        instructions = instructions == null ? "" : instructions;
    }

    public static LauncherRegistration ready(String status) {
        return new LauncherRegistration(false, null, status, "");
    }

    public static LauncherRegistration importRequired(Path file, String launcherName) {
        return new LauncherRegistration(
                true,
                file,
                "Pendiente de importar",
                "En " + launcherName + ", usa Import from Filesystem / Import Profile y selecciona este archivo."
        );
    }
}
