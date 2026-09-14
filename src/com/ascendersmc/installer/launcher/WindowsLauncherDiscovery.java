package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.util.InstallerLogger;

import javax.swing.filechooser.FileSystemView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Inventario estricto de aplicaciones en Windows.
 *
 * Fuentes aceptadas como evidencia de instalación:
 *  1) Aplicaciones instaladas / Aplicaciones y características
 *     (claves Uninstall HKCU/HKLM, 32 y 64 bits) leídas con reg.exe.
 *  2) Paquetes AppX/MSIX del usuario (Microsoft Store), consultados con
 *     PowerShell -EncodedCommand para evitar problemas de quoting/parsing.
 *  3) Accesos directos .lnk originales de Menú Inicio/Escritorio,
 *     enumerados directamente por el filesystem.
 *
 * NO usa rutas adivinadas, procesos activos, protocolos, carpetas residuales
 * ni escaneos recursivos buscando ejecutables/JARs como evidencia.
 */
final class WindowsLauncherDiscovery {
    private static volatile WindowsLauncherDiscovery SHARED;
    private static final Object SHARED_LOCK = new Object();

    static WindowsLauncherDiscovery shared() {
        WindowsLauncherDiscovery local = SHARED;
        if (local != null) return local;
        synchronized (SHARED_LOCK) {
            if (SHARED == null) {
                InstallerLogger.debug("DETECT", "inventory.cache.miss | construyendo inventario Windows");
                SHARED = new WindowsLauncherDiscovery();
            } else {
                InstallerLogger.debug("DETECT", "inventory.cache.hit-after-lock");
            }
            return SHARED;
        }
    }

    static void warmUpAsync() {
        if (SHARED != null || !isWindows()) return;
        Thread t = new Thread(() -> {
            try { shared(); }
            catch (Throwable ex) { InstallerLogger.warn("DETECT", "inventory.warmup.fail | " + ex.getMessage()); }
        }, "ascendersmc-windows-inventory");
        t.setDaemon(true);
        t.start();
    }

    record Shortcut(Path shortcut, String name) {}
    record InstalledApp(String displayName, String installLocation, String displayIcon, String uninstallString, String registryKey) {}
    record AppxPackage(String name, String packageFamilyName, String installLocation) {}

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(12);

    private final List<Shortcut> shortcuts;
    private final List<InstalledApp> installedApps;
    private final List<AppxPackage> appxPackages;

    WindowsLauncherDiscovery() {
        if (!isWindows()) {
            shortcuts = List.of();
            installedApps = List.of();
            appxPackages = List.of();
            return;
        }

        installedApps = discoverInstalledAppsFromRegistry();
        appxPackages = discoverAppxPackages();
        shortcuts = discoverShortcutsDirect();

        InstallerLogger.info("DETECT", "Inventario Windows listo | installedApps=" + installedApps.size()
                + " | appx=" + appxPackages.size() + " | shortcuts=" + shortcuts.size());
    }

    Optional<Shortcut> findShortcut(String... tokens) {
        return shortcuts.stream()
                .filter(s -> matchesAny((safe(s.name()) + " " + s.shortcut()).toLowerCase(Locale.ROOT), tokens))
                .sorted(Comparator.comparingInt((Shortcut s) -> shortcutScore(s, tokens)).reversed())
                .findFirst();
    }

    Optional<InstalledApp> findInstalledApp(String... tokens) {
        return installedApps.stream()
                .filter(app -> matchesAny(safe(app.displayName()).toLowerCase(Locale.ROOT), tokens))
                .sorted(Comparator.comparingInt((InstalledApp app) -> installedAppScore(app, tokens)).reversed())
                .findFirst();
    }

    Optional<AppxPackage> findAppxPackage(String... packageNamesOrFamilies) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String q : packageNamesOrFamilies) {
            if (q != null && !q.isBlank()) wanted.add(q.toLowerCase(Locale.ROOT));
        }
        return appxPackages.stream().filter(pkg -> {
            String name = safe(pkg.name()).toLowerCase(Locale.ROOT);
            String family = safe(pkg.packageFamilyName()).toLowerCase(Locale.ROOT);
            for (String q : wanted) {
                if (name.equals(q) || family.equals(q) || family.startsWith(q + "_")) return true;
            }
            return false;
        }).findFirst();
    }

    Optional<Path> executableFromInstalledApp(InstalledApp app, String... exactNames) {
        if (app == null) return Optional.empty();

        Path displayIcon = parseExecutableFromCommand(app.displayIcon());
        if (isExactExecutable(displayIcon, exactNames)) return Optional.of(displayIcon);

        Path installLocation = pathOrNull(app.installLocation());
        if (installLocation != null && Files.isDirectory(installLocation)) {
            for (String name : exactNames) {
                if (name == null || name.isBlank()) continue;
                Path candidate = installLocation.resolve(name);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    Optional<Path> installedAppLocation(InstalledApp app) {
        if (app == null) return Optional.empty();
        Path p = pathOrNull(app.installLocation());
        return p != null && Files.isDirectory(p) ? Optional.of(p) : Optional.empty();
    }

    private List<Shortcut> discoverShortcutsDirect() {
        long begin = System.nanoTime();
        LinkedHashSet<Path> recursiveRoots = new LinkedHashSet<>();
        LinkedHashSet<Path> shallowRoots = new LinkedHashSet<>();
        addDirectory(recursiveRoots, envPath("APPDATA", "Microsoft", "Windows", "Start Menu", "Programs"));
        addDirectory(recursiveRoots, envPath("ProgramData", "Microsoft", "Windows", "Start Menu", "Programs"));
        addDirectory(shallowRoots, envPath("USERPROFILE", "Desktop"));
        addDirectory(shallowRoots, envPath("PUBLIC", "Desktop"));
        addDirectory(shallowRoots, envPath("OneDrive", "Desktop"));

        LinkedHashMap<String, Shortcut> dedup = new LinkedHashMap<>();
        for (Path root : recursiveRoots) collectShortcuts(root, 6, dedup);
        for (Path root : shallowRoots) collectShortcuts(root, 1, dedup);

        List<Shortcut> out = new ArrayList<>(dedup.values());
        out.sort(Comparator.comparing(s -> s.shortcut().toString(), String.CASE_INSENSITIVE_ORDER));
        long ms = (System.nanoTime() - begin) / 1_000_000L;
        InstallerLogger.info("DETECT", "Accesos directos originales analizados: " + out.size() + " | ms=" + ms);
        return List.copyOf(out);
    }

    private void collectShortcuts(Path root, int maxDepth, Map<String, Shortcut> out) {
        if (root == null || !Files.isDirectory(root)) return;
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String filename = file.getFileName().toString();
                    if (filename.toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                        Path normalized = file.toAbsolutePath().normalize();
                        String key = normalized.toString().toLowerCase(Locale.ROOT);
                        out.putIfAbsent(key, new Shortcut(normalized, stripExtension(filename)));
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });
        } catch (Exception ex) {
            InstallerLogger.debug("DETECT", "shortcut.root.skip | root=" + root + " | " + ex.getClass().getSimpleName());
        }
    }

    /**
     * Lee directamente las claves usadas por el panel de aplicaciones clásico
     * para aplicaciones Win32/MSI. No depende de PowerShell.
     */
    private List<InstalledApp> discoverInstalledAppsFromRegistry() {
        List<InstalledApp> out = new ArrayList<>();
        queryUninstallRoot(out, "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "64");
        queryUninstallRoot(out, "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "32");
        queryUninstallRoot(out, "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "64");
        queryUninstallRoot(out, "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "32");

        LinkedHashMap<String, InstalledApp> dedup = new LinkedHashMap<>();
        for (InstalledApp app : out) {
            String key = (safe(app.displayName()) + "|" + safe(app.installLocation()) + "|" + safe(app.displayIcon())).toLowerCase(Locale.ROOT);
            dedup.putIfAbsent(key, app);
        }

        List<InstalledApp> result = List.copyOf(dedup.values());
        InstallerLogger.info("DETECT", "Aplicaciones instaladas (Apps y características/registro) analizadas: " + result.size());
        return result;
    }

    private void queryUninstallRoot(List<InstalledApp> out, String root, String regView) {
        List<String> command = new ArrayList<>();
        command.add("reg.exe");
        command.add("query");
        command.add(root);
        command.add("/s");
        if (regView != null) command.add("/reg:" + regView);

        CommandResult result = runCommand("installed-apps:" + (regView == null ? "HKCU" : "HKLM" + regView), command);
        if (result.exitCode() != 0) return;
        parseRegQuery(result.stdout(), out);
    }

    private void parseRegQuery(List<String> lines, List<InstalledApp> out) {
        String currentKey = null;
        String displayName = null;
        String installLocation = null;
        String displayIcon = null;
        String uninstallString = null;

        for (String raw : lines) {
            String line = raw == null ? "" : raw.stripTrailing();
            if (line.startsWith("HKEY_")) {
                if (currentKey != null && displayName != null && !displayName.isBlank()) {
                    out.add(new InstalledApp(displayName, nvl(installLocation), nvl(displayIcon), nvl(uninstallString), currentKey));
                }
                currentKey = line.trim();
                displayName = installLocation = displayIcon = uninstallString = null;
                continue;
            }

            if (currentKey == null || line.isBlank()) continue;
            RegistryValue value = parseRegistryValue(line);
            if (value == null) continue;
            switch (value.name().toLowerCase(Locale.ROOT)) {
                case "displayname" -> displayName = value.data();
                case "installlocation" -> installLocation = value.data();
                case "displayicon" -> displayIcon = value.data();
                case "uninstallstring", "quietuninstallstring" -> {
                    if (uninstallString == null || uninstallString.isBlank()) uninstallString = value.data();
                }
                default -> { }
            }
        }

        if (currentKey != null && displayName != null && !displayName.isBlank()) {
            out.add(new InstalledApp(displayName, nvl(installLocation), nvl(displayIcon), nvl(uninstallString), currentKey));
        }
    }

    private RegistryValue parseRegistryValue(String line) {
        String trimmed = line.trim();
        int typePos = indexOfRegistryType(trimmed);
        if (typePos < 0) return null;

        String name = trimmed.substring(0, typePos).trim();
        String rest = trimmed.substring(typePos).trim();
        int firstSpace = rest.indexOf(' ');
        if (firstSpace < 0) return new RegistryValue(name, "");
        String data = rest.substring(firstSpace).trim();
        return new RegistryValue(name, data);
    }

    private int indexOfRegistryType(String line) {
        for (String type : List.of("REG_SZ", "REG_EXPAND_SZ", "REG_MULTI_SZ", "REG_DWORD", "REG_QWORD")) {
            int idx = line.indexOf(type);
            if (idx > 0) return idx;
        }
        return -1;
    }

    private record RegistryValue(String name, String data) {}

    /**
     * Las aplicaciones MSIX/AppX también forman parte de "Aplicaciones instaladas".
     * Se usa EncodedCommand para que el script no pueda romperse por comillas,
     * saltos de línea o el parser de ProcessBuilder/PowerShell.
     */
    private List<AppxPackage> discoverAppxPackages() {
        String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"
                + "$sep=[char]9;"
                + "Get-AppxPackage -ErrorAction SilentlyContinue | ForEach-Object {"
                + "$n=([string]$_.Name) -replace '[\t\r\n]',' ';"
                + "$f=([string]$_.PackageFamilyName) -replace '[\t\r\n]',' ';"
                + "$l=([string]$_.InstallLocation) -replace '[\t\r\n]',' ';"
                + "[Console]::WriteLine($n+$sep+$f+$sep+$l)"
                + "}";

        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        CommandResult result = runCommand("installed-apps:appx", List.of(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded));

        List<AppxPackage> out = new ArrayList<>();
        if (result.exitCode() == 0) {
            for (String line : result.stdout()) {
                String[] p = line.split("\\t", -1);
                if (p.length < 3 || p[0].isBlank()) continue;
                out.add(new AppxPackage(p[0].trim(), p[1].trim(), p[2].trim()));
            }
        }
        InstallerLogger.info("DETECT", "Aplicaciones instaladas AppX/MSIX analizadas: " + out.size());
        return List.copyOf(out);
    }

    private CommandResult runCommand(String label, List<String> command) {
        long start = System.nanoTime();
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        try {
            Process process = new ProcessBuilder(command).start();

            Thread outThread = new Thread(() -> readLines(process.getInputStream(), stdout), "detect-out-" + sanitizeThreadName(label));
            Thread errThread = new Thread(() -> readLines(process.getErrorStream(), stderr), "detect-err-" + sanitizeThreadName(label));
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            boolean done = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                InstallerLogger.warn("DETECT", "Consulta Windows excedió timeout: " + label);
                return new CommandResult(-2, List.copyOf(stdout), List.copyOf(stderr));
            }

            outThread.join(1500);
            errThread.join(1500);
            int exit = process.exitValue();
            long ms = (System.nanoTime() - start) / 1_000_000L;
            InstallerLogger.debug("DETECT", "windows-source | " + label + " | exit=" + exit + " | rows=" + stdout.size() + " | ms=" + ms);
            if (exit != 0) {
                InstallerLogger.warn("DETECT", "Consulta Windows falló: " + label + " | exit=" + exit
                        + (stderr.isEmpty() ? "" : " | error=" + stderr.get(0)));
            }
            return new CommandResult(exit, List.copyOf(stdout), List.copyOf(stderr));
        } catch (Exception ex) {
            InstallerLogger.warn("DETECT", "No se pudo consultar " + label + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return new CommandResult(-1, List.of(), List.of(ex.toString()));
        }
    }

    private static void readLines(java.io.InputStream in, List<String> target) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                synchronized (target) { target.add(line); }
            }
        } catch (Exception ignored) { }
    }

    private record CommandResult(int exitCode, List<String> stdout, List<String> stderr) {}

    private static int shortcutScore(Shortcut s, String[] tokens) {
        int score = 0;
        String n = safe(s.name()).toLowerCase(Locale.ROOT);
        String p = s.shortcut().toString().toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            String q = token.toLowerCase(Locale.ROOT);
            if (n.equals(q)) score += 100;
            else if (n.contains(q)) score += 55;
            if (p.contains(q)) score += 10;
        }
        if (n.contains("uninstall") || n.contains("desinstal")) score -= 300;
        return score;
    }

    private static int installedAppScore(InstalledApp app, String[] tokens) {
        int score = 0;
        String n = safe(app.displayName()).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            String q = token.toLowerCase(Locale.ROOT);
            if (n.equals(q)) score += 120;
            else if (n.contains(q)) score += 60;
        }
        return score;
    }

    private static boolean matchesAny(String haystack, String... tokens) {
        if (haystack == null) return false;
        String h = haystack.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token != null && !token.isBlank() && h.contains(token.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isExactExecutable(Path p, String... names) {
        if (p == null || !Files.isRegularFile(p)) return false;
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name != null && n.equals(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static Path parseExecutableFromCommand(String text) {
        if (text == null || text.isBlank()) return null;
        String s = text.trim();
        if (s.startsWith("\"")) {
            int end = s.indexOf('"', 1);
            if (end > 1) return pathOrNull(s.substring(1, end));
        }
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma);
        String lower = s.toLowerCase(Locale.ROOT);
        int exe = lower.indexOf(".exe");
        if (exe >= 0) s = s.substring(0, exe + 4);
        return pathOrNull(s.replace("\"", "").trim());
    }

    private static Path envPath(String env, String... parts) {
        String base = System.getenv(env);
        if (base == null || base.isBlank()) return null;
        Path p = Path.of(base);
        for (String part : parts) p = p.resolve(part);
        return p.toAbsolutePath().normalize();
    }

    private static void addDirectory(Set<Path> roots, Path p) {
        if (p != null && Files.isDirectory(p)) roots.add(p);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String expanded = expandEnv(value.trim().replace("\"", ""));
            return Path.of(expanded).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String expandEnv(String value) {
        String result = value;
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            result = result.replace("%" + e.getKey() + "%", e.getValue());
        }
        return result;
    }

    private static String sanitizeThreadName(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String nvl(String s) { return s == null ? "" : s; }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
}
