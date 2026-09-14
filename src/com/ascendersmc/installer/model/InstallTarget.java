package com.ascendersmc.installer.model;

import java.nio.file.Path;

public record InstallTarget(
        LauncherType launcherType,
        String displayName,
        boolean installed,
        Path minecraftDir,
        Path launcherRoot,
        Path instanceRoot,
        Path launcherExecutable,
        boolean registerAutomatically,
        String note
) {
    public InstallTarget {
        minecraftDir = minecraftDir.toAbsolutePath().normalize();
        if (launcherRoot != null) launcherRoot = launcherRoot.toAbsolutePath().normalize();
        if (instanceRoot != null) instanceRoot = instanceRoot.toAbsolutePath().normalize();
        if (launcherExecutable != null) launcherExecutable = launcherExecutable.toAbsolutePath().normalize();
    }

    public String availabilityText() {
        return installed ? "INSTALADO" : "NO INSTALADO";
    }

    @Override public String toString() { return displayName; }
}
