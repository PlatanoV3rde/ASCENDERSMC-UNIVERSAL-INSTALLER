package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.SimpleJson;
import java.nio.charset.StandardCharsets;
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

    public static void assertLauncherClosed(InstallTarget target) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        Path exe = target.launcherExecutable();
        if (exe == null) return;
        String expected = exe.toAbsolutePath().normalize().toString().replace('/', '\\').toLowerCase(Locale.ROOT);
        try (var processes = ProcessHandle.allProcesses()) {
            for (ProcessHandle p : processes.toList()) {
                if (p.pid() == ProcessHandle.current().pid()) continue;
                String command = p.info().command().orElse("");
                String actual = command.replace('/', '\\').toLowerCase(Locale.ROOT);
                if (actual.equals(expected) || (actual.endsWith("\\sklauncher.exe")
                        && expected.endsWith("\\sklauncher.exe"))) {
                    throw new IllegalStateException("Cierra SKLauncher 4.x para registrar la instancia automáticamente. "
                            + "El launcher abierto puede sobrescribir instances.json y perder el nuevo perfil.");
                }
            }
        }
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
        match.putIfAbsent("icon", "Grass"); // Known valid built-in icon in 4.0.54
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
