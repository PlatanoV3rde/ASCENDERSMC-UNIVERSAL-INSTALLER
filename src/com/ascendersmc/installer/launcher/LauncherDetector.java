package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.model.LauncherType;
import com.ascendersmc.installer.util.InstallerLogger;

import java.nio.file.*;
import java.util.*;

/**
 * Detector estricto de launchers basado únicamente en evidencia que Windows
 * registra como aplicación instalada, AppX/MSIX o shortcut original.
 *
 * El inventario de Windows se comparte durante toda la sesión. Cambiar entre
 * COBBLEWORLD y PIXELMON no vuelve a consultar registro/AppX/shortcuts.
 */
public final class LauncherDetector {
    private final InstallerConfig config;

    public LauncherDetector(InstallerConfig config) {
        this.config = config;
    }

    public static void warmUpWindowsInventory() {
        WindowsLauncherDiscovery.warmUpAsync();
    }

    public List<InstallTarget> detect(InstallerProfile profile) {
        long begin = System.nanoTime();
        WindowsLauncherDiscovery windows = WindowsLauncherDiscovery.shared();
        InstallerLogger.debug("DETECT", "scan.start | profile=" + profile.displayName()
                + " | policy=STRICT_WINDOWS_EVIDENCE_ONLY | inventory=cached");

        List<InstallTarget> result = new ArrayList<>();
        for (LauncherType type : List.of(
                LauncherType.CURSEFORGE,
                LauncherType.PRISM,
                LauncherType.SKLAUNCHER,
                LauncherType.MODRINTH,
                LauncherType.TLAUNCHER,
                LauncherType.OFFICIAL)) {
            InstallTarget target = detectOne(profile, type, windows);
            result.add(target);
            InstallerLogger.info("DETECT", type.label() + " => " + (target.installed() ? "INSTALADO" : "NO INSTALADO")
                    + (target.launcherExecutable() == null ? "" : " | entrada=" + target.launcherExecutable()));
            InstallerLogger.debug("DETECT", "target | type=" + type.id()
                    + " | root=" + target.launcherRoot()
                    + " | instanceRoot=" + target.instanceRoot()
                    + " | gameDir=" + target.minecraftDir()
                    + " | autoRegister=" + target.registerAutomatically()
                    + " | note=" + target.note());
        }

        result.sort(Comparator
                .comparing((InstallTarget t) -> !t.installed())
                .thenComparingInt(t -> launcherOrder(t.launcherType())));

        long installed = result.stream().filter(InstallTarget::installed).count();
        long ms = (System.nanoTime() - begin) / 1_000_000L;
        InstallerLogger.debug("DETECT", "scan.end | total=" + result.size() + " | installed=" + installed + " | ms=" + ms);
        return result;
    }

    private InstallTarget detectOne(InstallerProfile profile, LauncherType type, WindowsLauncherDiscovery windows) {
        String[] tokens = tokens(type);
        String[] exeNames = executableNames(type);

        Optional<WindowsLauncherDiscovery.Shortcut> shortcut = windows.findShortcut(tokens);
        Optional<WindowsLauncherDiscovery.InstalledApp> installedApp = windows.findInstalledApp(tokens);
        Optional<WindowsLauncherDiscovery.AppxPackage> appx = type == LauncherType.OFFICIAL
                ? windows.findAppxPackage("Microsoft.4297127D64EC6", "Microsoft.MinecraftLauncher")
                : Optional.empty();

        boolean installed = installedApp.isPresent() || appx.isPresent() || shortcut.isPresent();

        Path entry = null;
        if (installedApp.isPresent()) {
            entry = windows.executableFromInstalledApp(installedApp.get(), exeNames).orElse(null);
        }
        if (entry == null) entry = shortcut.map(WindowsLauncherDiscovery.Shortcut::shortcut).orElse(null);

        List<String> sources = new ArrayList<>();
        shortcut.ifPresent(v -> sources.add("SHORTCUT:" + v.shortcut()));
        installedApp.ifPresent(v -> sources.add("INSTALLED_APP:" + v.displayName()));
        appx.ifPresent(v -> sources.add("APPX:" + v.packageFamilyName()));
        InstallerLogger.debug("DETECT", "evidence | launcher=" + type.label() + " | "
                + (sources.isEmpty() ? "NONE" : String.join(" | ", sources)));

        Path minecraftDir = config.launcherProfileDir(profile, type);
        Path launcherRoot = authoritativeLauncherRoot(windows, installedApp.orElse(null));
        Path instanceRoot = null;
        boolean autoRegister = false;

        if (installed) {
            switch (type) {
                case OFFICIAL -> {
                    launcherRoot = envPath("APPDATA", ".minecraft");
                    autoRegister = launcherRoot != null;
                }
                case SKLAUNCHER -> {
                    // SKLauncher 3.2 usa el mismo launcher_profiles.json que el
                    // launcher oficial y permite aislar cada instalación con
                    // Game Directory. Registramos un perfil ASCENDERSMC en
                    // %APPDATA%\.minecraft que apunta a nuestra carpeta aislada.
                    launcherRoot = envPath("APPDATA", ".minecraft");
                    instanceRoot = minecraftDir;
                    autoRegister = launcherRoot != null;
                }
                case PRISM -> {
                    Path prismData = appDataConfigRoot("PrismLauncher", "prismlauncher.cfg");
                    if (prismData != null) {
                        launcherRoot = prismData;
                        Path instances = resolveInstanceDir(prismData, "prismlauncher.cfg");
                        instanceRoot = instances.resolve(instanceName(profile));
                        minecraftDir = instanceRoot.resolve("minecraft");
                        autoRegister = true;
                    }
                }
                case TLAUNCHER -> {
                    Path tlauncherRoot = resolveTLauncherMinecraftRoot(launcherRoot, entry);
                    if (tlauncherRoot != null) {
                        launcherRoot = tlauncherRoot;
                        instanceRoot = tlauncherRoot.resolve("versions").resolve(instanceName(profile));
                        minecraftDir = instanceRoot;
                        autoRegister = true;
                    }
                }
                case CURSEFORGE -> {
                    Path cfRoot = resolveCurseForgeMinecraftRoot();
                    if (cfRoot != null) {
                        launcherRoot = cfRoot;
                        Path instances = cfRoot.resolve("Instances");
                        Path nativeProfile = findCurseForgeAscendersProfile(instances, profile);
                        instanceRoot = nativeProfile != null
                                ? nativeProfile
                                : instances.resolve(instanceName(profile));
                        // La primera instalación se prepara en staging. El registrador
                        // 0.7.0 crea después bajo Instances un custom profile completo
                        // usando el esquema real de CurseForge.
                        minecraftDir = nativeProfile != null
                                ? nativeProfile
                                : config.launcherProfileDir(profile, type);
                        autoRegister = true;
                    }
                }
                case MODRINTH -> {
                    Path modrinthRoot = envPath("APPDATA", "ModrinthApp");
                    if (modrinthRoot != null) {
                        launcherRoot = modrinthRoot;
                        instanceRoot = modrinthRoot.resolve("profiles").resolve(instanceName(profile));
                        minecraftDir = Files.isDirectory(instanceRoot)
                                ? instanceRoot
                                : config.launcherProfileDir(profile, type);
                        autoRegister = true;
                    }
                }
                default -> { }
            }
        }

        String note = installed
                ? type.label() + " detectado mediante evidencia registrada por Windows."
                : type.label() + " no aparece en accesos directos ni en aplicaciones instaladas de Windows.";

        return new InstallTarget(type, type.label(), installed, minecraftDir, launcherRoot, instanceRoot,
                entry, autoRegister, note);
    }

    private Path resolveTLauncherMinecraftRoot(Path installedLocation, Path entry) {
        if (installedLocation != null && installedLocation.getFileName() != null
                && installedLocation.getFileName().toString().equalsIgnoreCase(".minecraft")) {
            return installedLocation;
        }
        if (entry != null && Files.isRegularFile(entry) && entry.getFileName().toString().equalsIgnoreCase("TLauncher.exe")) {
            Path parent = entry.getParent();
            if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equalsIgnoreCase(".minecraft")) {
                return parent;
            }
        }
        // La evidencia de instalación sigue siendo Windows. Esta ruta solo
        // determina dónde TLauncher almacena Minecraft después de ser detectado.
        return envPath("APPDATA", ".minecraft");
    }

    private Path resolveCurseForgeMinecraftRoot() {
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile == null || userProfile.isBlank()) return null;
        Path standard = Path.of(userProfile, "curseforge", "minecraft").toAbsolutePath().normalize();
        InstallerLogger.debug("DETECT", "curseforge.moddingRoot | " + standard);
        return standard;
    }

    private Path authoritativeLauncherRoot(WindowsLauncherDiscovery windows,
                                           WindowsLauncherDiscovery.InstalledApp installedApp) {
        if (installedApp != null) {
            Optional<Path> location = windows.installedAppLocation(installedApp);
            if (location.isPresent()) return location.get();
        }
        return null;
    }

    private Path appDataConfigRoot(String folder, String configName) {
        Path root = envPath("APPDATA", folder);
        return root != null && Files.isRegularFile(root.resolve(configName)) ? root : null;
    }

    private Path configRootFromEvidence(Path launcherRoot, String configName) {
        if (launcherRoot == null || !Files.isDirectory(launcherRoot)) return null;
        return Files.isRegularFile(launcherRoot.resolve(configName)) ? launcherRoot : null;
    }

    private Path resolveInstanceDir(Path root, String configName) {
        Path cfg = root.resolve(configName);
        if (!Files.isRegularFile(cfg)) return root.resolve("instances");
        try {
            for (String line : Files.readAllLines(cfg)) {
                if (!line.startsWith("InstanceDir=")) continue;
                String value = line.substring("InstanceDir=".length()).trim();
                if (value.isBlank()) break;
                Path p = Path.of(value);
                return p.isAbsolute() ? p.normalize() : root.resolve(p).normalize();
            }
        } catch (Exception ex) {
            InstallerLogger.warn("DETECT", "No se pudo leer " + cfg + ": " + ex.getMessage());
        }
        return root.resolve("instances");
    }

    private Path findCurseForgeAscendersProfile(Path instancesRoot, InstallerProfile profile) {
        if (instancesRoot == null || !Files.isDirectory(instancesRoot)) return null;
        String profileName = profile.displayName().toLowerCase(Locale.ROOT);
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(instancesRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path metadata = dir.resolve("minecraftinstance.json");
                if (!Files.isRegularFile(metadata)) continue;

                // 0.7.0 vuelve a usar registro directo, pero ahora el marcador
                // solo se escribe DESPUÉS de generar y validar un minecraftinstance.json
                // con el mismo esquema de un custom profile real de CurseForge.
                if (Files.isRegularFile(dir.resolve(CurseForgeRegistrar.NATIVE_MARKER))) {
                    try {
                        String marker = Files.readString(dir.resolve(CurseForgeRegistrar.NATIVE_MARKER)).toLowerCase(Locale.ROOT);
                        if (marker.contains("profile=" + profile.name().toLowerCase(Locale.ROOT))
                                && marker.contains("schema=curseforge-custom-profile")) {
                            InstallerLogger.debug("DETECT", "curseforge.nativeProfile.direct | " + dir);
                            return dir;
                        }
                    } catch (Exception ignored) { }
                }

                try {
                    String raw = Files.readString(metadata).toLowerCase(Locale.ROOT);
                    if (raw.contains("ascendersmc") && raw.contains(profileName)) {
                        // Carpetas de versiones viejas del Installer incluían este
                        // archivo aunque CurseForge nunca las hubiese registrado.
                        if (Files.isRegularFile(dir.resolve("ASCENDERSMC-PROFILE.txt"))) {
                            InstallerLogger.debug("DETECT", "curseforge.legacy-unregistered.skip | " + dir);
                            continue;
                        }
                        candidates.add(dir);
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ex) {
            InstallerLogger.debug("DETECT", "curseforge.profile.scan.failed | " + ex.getMessage());
        }

        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Long.compare(lastModified(b.resolve("minecraftinstance.json")), lastModified(a.resolve("minecraftinstance.json"))));
        Path selected = candidates.get(0);
        InstallerLogger.debug("DETECT", "curseforge.nativeProfile.metadata | " + selected);
        return selected;
    }

    private long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception ignored) { return 0L; }
    }

    private String instanceName(InstallerProfile profile) {
        return "ASCENDERSMC-" + profile.displayName();
    }

    private Path envPath(String env, String... parts) {
        String base = System.getenv(env);
        if (base == null || base.isBlank()) return null;
        Path p = Path.of(base);
        for (String part : parts) p = p.resolve(part);
        return p.toAbsolutePath().normalize();
    }

    private String[] tokens(LauncherType type) {
        return switch (type) {
            case CURSEFORGE -> new String[]{"curseforge"};
            case PRISM -> new String[]{"prism launcher", "prismlauncher"};
            case SKLAUNCHER -> new String[]{"sklauncher", "sk launcher"};
            case MODRINTH -> new String[]{"modrinth app", "modrinth"};
            case TLAUNCHER -> new String[]{"tlauncher"};
            case OFFICIAL -> new String[]{"minecraft launcher"};
        };
    }

    private String[] executableNames(LauncherType type) {
        return switch (type) {
            case CURSEFORGE -> new String[]{"CurseForge.exe"};
            case PRISM -> new String[]{"prismlauncher.exe"};
            case SKLAUNCHER -> new String[]{"SKlauncher.exe", "SKLauncher.exe"};
            case MODRINTH -> new String[]{"Modrinth App.exe", "ModrinthApp.exe"};
            case TLAUNCHER -> new String[]{"TLauncher.exe"};
            case OFFICIAL -> new String[]{"MinecraftLauncher.exe"};
        };
    }

    private int launcherOrder(LauncherType type) {
        return switch (type) {
            case CURSEFORGE -> 1;
            case PRISM -> 2;
            case SKLAUNCHER -> 3;
            case MODRINTH -> 4;
            case TLAUNCHER -> 5;
            case OFFICIAL -> 6;
        };
    }
}
