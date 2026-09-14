package com.ascendersmc.installer.model;

public enum InstallerProfile {
    COBBLEWORLD("cobbleworld", "COBBLEWORLD"),
    PIXELMON("pixelmon", "PIXELMON");

    private final String id;
    private final String displayName;
    InstallerProfile(String id, String displayName) { this.id = id; this.displayName = displayName; }
    public String id() { return id; }
    public String displayName() { return displayName; }
}
