package com.ascendersmc.installer.model;

import java.awt.*;

public enum LauncherType {
    CURSEFORGE("curseforge", "CurseForge", "CF", new Color(241, 100, 39), false),
    PRISM("prism", "Prism Launcher", "PR", new Color(0, 197, 167), true),
    SKLAUNCHER("sklauncher", "SKLauncher", "SK", new Color(37, 166, 255), false),
    MODRINTH("modrinth", "Modrinth App", "MR", new Color(29, 214, 117), false),
    TLAUNCHER("tlauncher", "TLauncher", "TL", new Color(18, 175, 252), false),
    OFFICIAL("official", "Minecraft Launcher", "MC", new Color(111, 196, 73), true);

    private final String id;
    private final String label;
    private final String shortLabel;
    private final Color accent;
    private final boolean automaticRegistration;

    LauncherType(String id, String label, String shortLabel, Color accent, boolean automaticRegistration) {
        this.id = id;
        this.label = label;
        this.shortLabel = shortLabel;
        this.accent = accent;
        this.automaticRegistration = automaticRegistration;
    }

    public String id() { return id; }
    public String label() { return label; }
    public String shortLabel() { return shortLabel; }
    public Color accent() { return accent; }
    public boolean automaticRegistration() { return automaticRegistration; }
    @Override public String toString() { return label; }
}
