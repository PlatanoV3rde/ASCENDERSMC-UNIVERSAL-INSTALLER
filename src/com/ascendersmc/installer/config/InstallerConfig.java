package com.ascendersmc.installer.config;

import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.model.LauncherType;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public final class InstallerConfig {
    public record ProfileConfig(
            InstallerProfile profile,
            String modpackReleaseApi,
            String modpackAssetName,
            String modpackAssetPrefix,
            String minecraftVersion,
            String neoForgeVersion,
            String resourcePackReleaseApi,
            String resourcePackAssetName
    ) {}

    private final Properties properties;
    private final Path baseDir;

    private InstallerConfig(Properties properties, Path baseDir) {
        this.properties = properties;
        this.baseDir = baseDir;
    }

    public static InstallerConfig load() throws IOException {
        Properties p = new Properties();
        Path external = Path.of(System.getProperty("user.dir")).resolve("installer.properties");
        if (Files.isRegularFile(external)) {
            try (InputStream in = Files.newInputStream(external)) { p.load(in); }
        } else {
            try (InputStream in = InstallerConfig.class.getResourceAsStream("/installer.properties")) {
                if (in == null) throw new FileNotFoundException("No se encontró installer.properties");
                p.load(in);
            }
        }

        String explicit = p.getProperty("install.base.dir", "").trim();
        Path base;
        if (!explicit.isBlank()) {
            base = Path.of(explicit);
        } else if (isWindows() && env("APPDATA") != null) {
            base = Path.of(env("APPDATA"), "ASCENDERSMC", "UniversalInstaller");
        } else {
            base = Path.of(System.getProperty("user.home"), ".ascendersmc", "UniversalInstaller");
        }
        return new InstallerConfig(p, base.toAbsolutePath().normalize());
    }

    public Path baseDir() { return baseDir; }
    public Path logsDir() { return baseDir.resolve("logs"); }
    public Path cacheDir() { return baseDir.resolve("cache"); }
    public Path importsDir() { return baseDir.resolve("imports"); }

    public Path profileRootDir(InstallerProfile profile, LauncherType launcherType) {
        return baseDir.resolve("profiles").resolve(profile.id()).resolve(launcherType.id());
    }

    public Path launcherProfileDir(InstallerProfile profile, LauncherType launcherType) {
        return profileRootDir(profile, launcherType).resolve("minecraft");
    }

    public Path universalProfileDir(InstallerProfile profile) {
        return launcherProfileDir(profile, LauncherType.OFFICIAL);
    }

    public Path stateDir(InstallerProfile profile) {
        return baseDir.resolve("profiles").resolve(profile.id()).resolve("state");
    }
    public String serverHost() { return properties.getProperty("server.host", "ascendersmc.online").trim(); }
    public int serverPort() { return parseInt(properties.getProperty("server.port"), 25565); }
    public int guiScale() { return Math.max(0, parseInt(properties.getProperty("first.run.gui.scale"), 2)); }
    public String serverResourcePackMode() { return properties.getProperty("server.resourcepack.mode", "NO").trim().toUpperCase(); }
    public boolean debugEnabled() { return Boolean.parseBoolean(properties.getProperty("debug.enabled", "true").trim()); }

    public ProfileConfig profile(InstallerProfile profile) {
        String key = "profile." + profile.id() + ".";
        return new ProfileConfig(
                profile,
                req(key + "modpack.release.api"),
                properties.getProperty(key + "modpack.asset.name", "").trim(),
                req(key + "modpack.asset.prefix"),
                properties.getProperty(key + "minecraft.version", "1.21.1").trim(),
                properties.getProperty(key + "neoforge.version", "21.1.248").trim(),
                req(key + "resourcepack.release.api"),
                req(key + "resourcepack.asset.name")
        );
    }

    private String req(String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isBlank()) throw new IllegalStateException("Falta " + key + " en installer.properties");
        return value;
    }
    private static int parseInt(String s, int defaultValue) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); }
        catch (Exception ignored) { return defaultValue; }
    }
    private static String env(String key) { String v = System.getenv(key); return v == null || v.isBlank() ? null : v; }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
}
