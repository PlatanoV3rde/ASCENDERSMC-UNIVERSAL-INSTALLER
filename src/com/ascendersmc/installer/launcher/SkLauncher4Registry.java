package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.SimpleJson;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * SKLauncher 4.x (tested format from 4.0.54).
 * Real instance membership is stored in <launcherRoot>/instances.json, NOT in
 * launcher_profiles.json or by the existence of a directory.
 *
 * CAUTION: this is an external/private data format; if SKLauncher changes its
 * schema, fail explicitly instead of overwriting an unfamiliar registry.
 */
public final class SkLauncher4Registry {
    private SkLauncher4Registry() { }

    public static String id(InstallerProfile profile) {
        return "ascendersmc-" + profile.id();
    }

    public static Path registryPath(Path instancesDir) {
        if (instancesDir == null || instancesDir.getParent() == null)
            throw new IllegalArgumentException("Directorio de instancias de SKLauncher 4 inválido");
        return instancesDir.toAbsolutePath().normalize().getParent().resolve("instances.json");
    }

    /** Identify only processes running from the exact installed launcher executable.
     *  Never match Java/Minecraft game processes, other unrelated executables, or a
     *  different SKLauncher installation using the same filename.
     */
    static boolean executableMatches(String runningCommand, Path expectedExecutable) {
        if (runningCommand == null || runningCommand.isBlank() || expectedExecutable == null) return false;
        String running = runningCommand.replace('/', '\\').toLowerCase(Locale.ROOT);
        String expected = expectedExecutable.toAbsolutePath().normalize().toString()
                .replace('/', '\\').toLowerCase(Locale.ROOT);
        return running.equals(expected);
    }

    public static List<ProcessHandle> runningLauncherProcesses(InstallTarget target) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                || target == null || target.launcherExecutable() == null) return List.of();
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.filter(p -> p.pid() != ProcessHandle.current().pid() && p.isAlive()
                    && executableMatches(p.info().command().orElse(null), target.launcherExecutable()))
                    .toList();
        }
    }

    /** Must be called only AFTER the user agrees to close background processes.
     *  Java/Windows process termination can discard unsaved launcher state, so
     *  no background process is ever terminated without an explicit prompt.
     */
    public static void closeLauncherProcessesWithConsent(InstallTarget target) throws Exception {
        List<ProcessHandle> processes = runningLauncherProcesses(target);
        if (processes.isEmpty()) return;
        InstallerLogger.info("SKLAUNCHER4", "Cierre autorizado de " + processes.size()
                + " proceso(s) SKLauncher: " + processes.stream().map(p -> Long.toString(p.pid())).toList());
        for (ProcessHandle process : processes) {
            if (process.isAlive() && !process.destroy())
                InstallerLogger.warn("SKLAUNCHER4", "No se pudo solicitar cierre PID=" + process.pid());
        }
        for (ProcessHandle process : processes) {
            if (!process.isAlive()) continue;
            try {
                process.onExit().get(5, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                InstallerLogger.warn("SKLAUNCHER4", "PID sigue activo; solicitando cierre forzado: " + process.pid());
                process.destroyForcibly();
                process.onExit().get(5, TimeUnit.SECONDS);
            }
        }
        if (!runningLauncherProcesses(target).isEmpty())
            throw new IllegalStateException("SKLauncher continúa ejecutándose tras intentar cerrarlo. "
                    + "No se modificarán sus instancias hasta cerrarlo de forma segura.");
        InstallerLogger.info("SKLAUNCHER4", "Todos los procesos de SKLauncher cerrados antes de instalar");
    }

    public static void assertLauncherClosed(InstallTarget target) {
        List<ProcessHandle> processes = runningLauncherProcesses(target);
        if (!processes.isEmpty()) {
            InstallerLogger.warn("SKLAUNCHER4", "SKLauncher permanece en segundo plano | pids="
                    + processes.stream().map(p -> Long.toString(p.pid())).toList());
            throw new IllegalStateException("SKLauncher sigue ejecutándose (también puede quedar en segundo plano). "
                    + "Autoriza su cierre desde ASCENDERSMC Installer para proteger instances.json.");
        }
    }

    /** Save the bundled ASCENDERSMC 256px PNG where SKLauncher 4.0.54 stores its
     *  real custom icons. A real instance uses starship-icon://icons/<id>/<hash>.png.
     */
    static String ensureInstanceIcon(Path instancesDir, InstallerProfile profile) throws Exception {
        String name = profile == InstallerProfile.COBBLEWORLD ? "profile-cobbleworld.png" : "profile-pixelmon.png";
        byte[] png;
        try (InputStream input = SkLauncher4Registry.class.getResourceAsStream("/assets/" + name)) {
            if (input == null) throw new IOException("No está incluido el logo ASCENDERSMC: " + name);
            png = input.readAllBytes();
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(png));
        Path launcherRoot = instancesDir.toAbsolutePath().normalize().getParent();
        Path iconsRoot = launcherRoot.resolve("icons");
        Path iconDir = iconsRoot.resolve(id(profile));
        if (Files.isSymbolicLink(iconsRoot) || Files.isSymbolicLink(iconDir))
            throw new SecurityException("Directorio de iconos SKLauncher es enlace simbólico");
        Files.createDirectories(iconDir);
        Path icon = iconDir.resolve(digest + ".png");
        if (Files.isSymbolicLink(icon)) throw new SecurityException("Icono SKLauncher es enlace simbólico");
        if (!Files.isRegularFile(icon) || !Arrays.equals(Files.readAllBytes(icon), png)) {
            Path pending = Files.createTempFile(iconDir, "ascendersmc-icon-", ".tmp");
            try {
                Files.write(pending, png);
                try {
                    Files.move(pending, icon, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(pending, icon, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(pending); }
            InstallerLogger.info("SKLAUNCHER4", "Logo de instancia guardado | " + icon);
        }
        return "starship-icon://icons/" + id(profile) + "/" + digest + ".png";
    }

    private static boolean shouldApplyBrandIcon(Object existing) {
        if (!(existing instanceof String s) || s.isBlank()) return true;
        return s.equalsIgnoreCase("Grass") || s.equalsIgnoreCase("Dirt")
                || s.equalsIgnoreCase("Default") || s.equalsIgnoreCase("Unknown")
                || s.startsWith("starship-icon://icons/ascendersmc-");
    }

    /** Returns only a profile actually listed in instances.json, never an orphan directory. */
    public static Path findRegisteredInstance(Path instancesDir, InstallerProfile profile) {
        if (instancesDir == null || profile == null) return null;
        Path registry = registryPath(instancesDir);
        if (!Files.isRegularFile(registry)) return null;
        try {
            Map<String,Object> root = readRoot(registry);
            for (Map<String,Object> entry : instances(root)) {
                if (!id(profile).equalsIgnoreCase(String.valueOf(entry.get("id")))) continue;
                Object directory = entry.get("directory");
                if (!(directory instanceof String s) || s.isBlank()) return null;
                Path path = Path.of(s).toAbsolutePath().normalize();
                if (!path.startsWith(instancesDir.toAbsolutePath().normalize())) {
                    InstallerLogger.warn("SKLAUNCHER4", "Instancia registrada fuera del directorio permitido: " + path);
                    return null;
                }
                if (Files.isDirectory(path)) return path;
                return null;
            }
        } catch (Exception e) {
            InstallerLogger.warn("SKLAUNCHER4", "No se pudo leer instances.json; no se asumirá registro: " + e.getMessage());
        }
        return null;
    }

    public static void register(Path instancesDir, InstallerProfile profile,
                                String minecraftVersion, String neoForgeVersion,
                                Path gameDir) throws Exception {
        Path normalizedInstances = instancesDir.toAbsolutePath().normalize();
        Path target = gameDir.toAbsolutePath().normalize();
        if (!target.startsWith(normalizedInstances) || target.equals(normalizedInstances))
            throw new SecurityException("SKLauncher 4 requiere una instancia aislada dentro de " + normalizedInstances);
        if (!Files.isDirectory(target) || Files.isSymbolicLink(target))
            throw new IllegalStateException("La carpeta de instancia no existe o no es segura: " + target);
        Path registry = registryPath(normalizedInstances);
        Files.createDirectories(registry.getParent());
        if (Files.isSymbolicLink(registry)) throw new SecurityException("instances.json no puede ser un enlace simbólico");

        Map<String,Object> root = Files.isRegularFile(registry)
                ? readRoot(registry)
                : new LinkedHashMap<>(Map.of("instances", new ArrayList<>()));
        List<Map<String,Object>> entries = instances(root);

        Map<String,Object> match = null;
        for (Map<String,Object> entry : entries) {
            if (!id(profile).equalsIgnoreCase(String.valueOf(entry.get("id")))) continue;
            if (match != null) throw new IllegalStateException("Hay dos entradas ASCENDERSMC con el mismo id");
            match = entry;
        }
        boolean update = match != null;
        if (!update) {
            match = new LinkedHashMap<>();
            entries.add(match);
        } else {
            Object path = match.get("directory");
            if (path instanceof String s && !s.isBlank()
                    && !Path.of(s).toAbsolutePath().normalize().equals(target))
                throw new IllegalStateException("Instancia ASCENDERSMC existente en otro directorio. "
                        + "No sobrescribir: " + s);
        }

        match.put("id", id(profile));
        match.put("name", "ASCENDERSMC " + profile.displayName());
        match.put("type", "custom");
        match.put("installComplete", true);
        // Respect a custom icon the player selected, replace default/ASCENDERSMC icons.
        // The PNG is copied to the native SKLauncher icon cache before the registry write.
        String brandIcon = ensureInstanceIcon(normalizedInstances, profile);
        if (shouldApplyBrandIcon(match.get("icon"))) match.put("icon", brandIcon);
        match.put("versionId", minecraftVersion + "-neoforge-" + neoForgeVersion);
        match.put("gameType", "neoforge");
        match.put("minecraftVersion", minecraftVersion);
        match.put("loaderVersion", neoForgeVersion);
        match.put("directory", target.toString());
        match.putIfAbsent("createdAt", Instant.now().toString());
        match.putIfAbsent("playTime", 0L);
        match.putIfAbsent("sessionCount", 0L);
        // Do not fake a Modrinth project/version ID; this is a custom pack.
        match.remove("modpackInfo");

        byte[] content = (SimpleJson.stringify(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        // This backup is separate from SKLauncher's own instances.json.bak.
        Path backup = registry.resolveSibling("instances.json.ascendersmc.bak");
        if (Files.isRegularFile(registry)) {
            Files.copy(registry, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Path pending = Files.createTempFile(registry.getParent(), "instances.ascendersmc-", ".tmp");
        try {
            Files.write(pending, content, StandardOpenOption.TRUNCATE_EXISTING);
            // Test JSON BEFORE replace; unknown data must remain untouched.
            readRoot(pending);
            try {
                Files.move(pending, registry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(pending, registry, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
        Path confirmed = findRegisteredInstance(normalizedInstances, profile);
        if (!target.equals(confirmed)) {
            // Best effort rollback; never report success on an invalid registry.
            if (Files.isRegularFile(backup))
                Files.copy(backup, registry, StandardCopyOption.REPLACE_EXISTING);
            else if (!update) Files.deleteIfExists(registry);
            throw new IllegalStateException("SKLauncher 4 no confirmó su instancia en instances.json");
        }
        InstallerLogger.info("SKLAUNCHER4", "Registro nativo " + (update ? "actualizado" : "creado")
                + " | id=" + id(profile) + " | directory=" + confirmed
                + " | Minecraft=" + minecraftVersion + " | NeoForge=" + neoForgeVersion);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> readRoot(Path path) throws Exception {
        Object parsed = SimpleJson.parse(Files.readString(path, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?,?> map)) throw new IllegalStateException("instances.json no es un objeto");
        Object rawInstances = map.get("instances");
        if (!(rawInstances instanceof List<?> list)) throw new IllegalStateException("Falta la lista instances");
        for (Object object : list) {
            if (!(object instanceof Map<?,?>))
                throw new IllegalStateException("Entrada de instancia inválida");
        }
        return (Map<String,Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> instances(Map<String,Object> root) {
        return (List<Map<String,Object>>) root.get("instances");
    }
}
