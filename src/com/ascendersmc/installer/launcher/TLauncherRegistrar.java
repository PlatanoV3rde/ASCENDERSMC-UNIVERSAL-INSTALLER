package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.Hashing;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.SimpleJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Registra un modpack local nativo de TLauncher. TLauncher distingue los
 * modpacks mediante TLauncherAdditional.json y puede mantener mods por versión
 * en versions/<id>/mods. No se usa la carpeta global .minecraft/mods.
 */
public final class TLauncherRegistrar {
    private TLauncherRegistrar() {}

    @SuppressWarnings("unchecked")
    public static void register(InstallerConfig.ProfileConfig profile, InstallTarget target) throws Exception {
        Path root = target.launcherRoot();
        Path instance = target.instanceRoot();
        if (root == null || instance == null) throw new IllegalArgumentException("TLauncher root/instance ausente");

        String baseId = "neoforge-" + profile.neoForgeVersion();
        Path baseJson = root.resolve("versions").resolve(baseId).resolve(baseId + ".json");
        if (!Files.isRegularFile(baseJson)) {
            throw new IllegalStateException("NeoForge no dejó el perfil base esperado para TLauncher: " + baseJson);
        }

        Files.createDirectories(instance);
        Files.createDirectories(instance.resolve("mods"));
        Files.createDirectories(instance.resolve("resourcepacks"));

        String id = "ASCENDERSMC-" + profile.profile().displayName();
        Map<String,Object> doc = flattenVersion(root, baseId);
        doc.put("id", id);
        // Los modpacks creados por TLauncher aparecen como versiones modificadas.
        doc.put("type", "modified");
        doc.put("time", Instant.now().toString());
        doc.put("releaseTime", Instant.now().toString());
        doc.put("skinVersion", false);
        doc.remove("inheritsFrom");
        // El JAR de la versión personalizada es una copia del vanilla correspondiente.
        doc.put("jar", profile.minecraftVersion());
        forceSingleIsolatedGameDir(doc, instance);
        Files.writeString(instance.resolve(id + ".json"), SimpleJson.stringify(doc), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        List<Map<String,Object>> mods = buildElements(instance, "mods", "mod");
        List<Map<String,Object>> resourcePacks = buildElements(instance, "resourcepacks", "resourcepack");

        Map<String,Object> version = new LinkedHashMap<>();
        version.put("mods", mods);
        version.put("resourcePacks", resourcePacks);
        version.put("maps", List.of());
        version.put("shaderpacks", List.of());
        // TLauncher historically calls this field forgeVersion even for local
        // modpack metadata. The actual Minecraft version JSON still points to
        // NeoForge, so this value is descriptive for its modpack manager.
        version.put("forgeVersion", profile.neoForgeVersion());
        version.put("gameVersion", profile.minecraftVersion());
        version.put("id", stableNegativeId(id + ":version"));
        version.put("name", profile.neoForgeVersion());

        Map<String,Object> modpack = new LinkedHashMap<>();
        modpack.put("modpackMemory", false);
        modpack.put("memory", 0);
        modpack.put("id", stableNegativeId(id));
        modpack.put("name", "ASCENDERSMC " + profile.profile().displayName());
        modpack.put("version", version);
        modpack.put("userInstall", false);
        modpack.put("populateStatus", false);

        Map<String,Object> additional = new LinkedHashMap<>();
        additional.put("activateSkinCapeForUserVersion", false);
        additional.put("additionalFiles", List.of());
        additional.put("tlauncherVersion", 0);
        additional.put("modpack", modpack);
        additional.put("source", "local_version_repo");
        additional.put("jar", profile.minecraftVersion());
        additional.put("skinVersion", false);

        Path additionalFile = instance.resolve("TLauncherAdditional.json");
        Files.writeString(additionalFile, SimpleJson.stringify(additional), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Path vanillaJar = root.resolve("versions").resolve(profile.minecraftVersion()).resolve(profile.minecraftVersion() + ".jar");
        Path localJar = instance.resolve(id + ".jar");
        if (!Files.exists(localJar) && Files.isRegularFile(vanillaJar)) {
            Files.copy(vanillaJar, localJar, StandardCopyOption.REPLACE_EXISTING);
        }

        InstallerLogger.debug("TLAUNCHER", "native-modpack.write | id=" + id
                + " | mods=" + mods.size() + " | resourcepacks=" + resourcePacks.size()
                + " | additional=" + additionalFile);
        InstallerLogger.info("LAUNCHER", "Perfil TLauncher registrado con mods aislados: " + id + " | versionDir=" + instance);
    }

    /**
     * Construye una versión autocontenida para TLauncher a partir de la cadena
     * inheritsFrom de NeoForge. TLauncher añade argumentos del padre al hijo;
     * por eso 0.6.2 terminaba con dos --gameDir. Al aplanar la cadena ya no hay
     * padre que pueda inyectar el directorio global.
     */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> flattenVersion(Path root, String versionId) throws Exception {
        return flattenVersion(root, versionId, new LinkedHashSet<>());
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> flattenVersion(Path root,
                                                     String versionId,
                                                     LinkedHashSet<String> chain) throws Exception {
        if (!chain.add(versionId)) throw new IllegalStateException("Herencia circular en versión TLauncher: " + chain);
        Path file = root.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Falta JSON heredado requerido por TLauncher: " + file);
        }
        Object parsed = SimpleJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?,?> raw)) throw new IllegalStateException("JSON de versión inválido: " + file);
        Map<String,Object> child = deepCopyMap((Map<String,Object>) raw);
        Object parentObj = child.get("inheritsFrom");
        if (!(parentObj instanceof String parentId) || parentId.isBlank()) {
            child.remove("inheritsFrom");
            return child;
        }

        Map<String,Object> parent = flattenVersion(root, parentId, chain);
        Map<String,Object> merged = mergeVersions(parent, child);
        merged.remove("inheritsFrom");
        InstallerLogger.debug("TLAUNCHER", "inherit.flatten | child=" + versionId + " | parent=" + parentId);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> mergeVersions(Map<String,Object> parent, Map<String,Object> child) throws Exception {
        Map<String,Object> merged = deepCopyMap(parent);

        for (Map.Entry<String,Object> e : child.entrySet()) {
            String key = e.getKey();
            if (key.equals("inheritsFrom") || key.equals("libraries") || key.equals("arguments")) continue;
            merged.put(key, deepCopyValue(e.getValue()));
        }

        List<Object> libraries = mergeLibraries(parent.get("libraries"), child.get("libraries"));
        if (!libraries.isEmpty()) merged.put("libraries", libraries);

        Map<String,Object> arguments = mergeArguments(parent.get("arguments"), child.get("arguments"));
        if (!arguments.isEmpty()) merged.put("arguments", arguments);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mergeLibraries(Object parentObj, Object childObj) throws Exception {
        LinkedHashMap<String,Object> byKey = new LinkedHashMap<>();
        if (parentObj instanceof List<?> list) {
            for (Object lib : list) byKey.put(libraryKey(lib), deepCopyValue(lib));
        }
        if (childObj instanceof List<?> list) {
            for (Object lib : list) byKey.put(libraryKey(lib), deepCopyValue(lib));
        }
        return new ArrayList<>(byKey.values());
    }

    private static String libraryKey(Object lib) {
        if (!(lib instanceof Map<?,?> map) || map.get("name") == null) return "anon:" + Objects.hashCode(lib);
        String name = String.valueOf(map.get("name"));
        String[] p = name.split(":");
        // Reemplazamos group:artifact conservando classifier, si existe. Esto
        // imita el reemplazo de librerías que hace el propio launcher.
        if (p.length >= 4) return p[0] + ":" + p[1] + ":" + p[3];
        if (p.length >= 2) return p[0] + ":" + p[1];
        return name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> mergeArguments(Object parentObj, Object childObj) throws Exception {
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String,Object> parent = parentObj instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
        Map<String,Object> child = childObj instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.addAll(parent.keySet());
        keys.addAll(child.keySet());
        for (String key : keys) {
            List<Object> combined = new ArrayList<>();
            Object a = parent.get(key);
            Object b = child.get(key);
            if (a instanceof List<?> l) for (Object v : l) combined.add(deepCopyValue(v));
            if (b instanceof List<?> l) for (Object v : l) combined.add(deepCopyValue(v));
            if (!combined.isEmpty()) out.put(key, combined);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void forceSingleIsolatedGameDir(Map<String,Object> doc, Path instance) {
        String isolated = instance.toAbsolutePath().normalize().toString();
        Object argumentsObj = doc.get("arguments");
        if (!(argumentsObj instanceof Map<?,?> argsRaw)) {
            InstallerLogger.warn("TLAUNCHER", "JSON aplanado no contiene arguments; no se pudo fijar --gameDir");
            return;
        }
        Map<String,Object> args = (Map<String,Object>) argsRaw;
        Object gameObj = args.get("game");
        if (!(gameObj instanceof List<?> gameRaw)) {
            InstallerLogger.warn("TLAUNCHER", "JSON aplanado no contiene arguments.game; no se pudo fijar --gameDir");
            return;
        }

        List<Object> game = new ArrayList<>((List<Object>) gameRaw);
        List<Object> cleaned = new ArrayList<>();
        boolean skipNextValue = false;
        int removed = 0;
        for (Object entry : game) {
            if (skipNextValue) {
                skipNextValue = false;
                removed++;
                continue;
            }
            if (containsToken(entry, "--gameDir")) {
                removed++;
                // En los JSON Mojang/TLauncher el valor va en la entrada
                // siguiente; quitamos el par completo y luego añadimos uno solo.
                skipNextValue = true;
                continue;
            }
            cleaned.add(entry);
        }
        cleaned.add("--gameDir");
        cleaned.add(isolated);
        args.put("game", cleaned);
        doc.put("arguments", args);

        // Si existiera el formato legacy, quitamos su gameDir para no crear un
        // segundo argumento junto al moderno.
        Object legacyObj = doc.get("minecraftArguments");
        if (legacyObj instanceof String legacy) {
            String sanitized = legacy
                    .replaceAll("(?i)--gameDir\\s+\\\"[^\\\"]*\\\"", "")
                    .replaceAll("(?i)--gameDir\\s+\\S+", "")
                    .replaceAll("\\s+", " ").trim();
            doc.put("minecraftArguments", sanitized);
        }
        InstallerLogger.debug("TLAUNCHER", "gameDir.single | " + isolated + " | removedEntries=" + removed);
    }

    private static boolean containsToken(Object entry, String token) {
        if (entry instanceof String s) return token.equals(s);
        if (entry instanceof Map<?,?> map) {
            Object values = map.get("values");
            if (values instanceof List<?> list) return list.stream().anyMatch(v -> token.equals(String.valueOf(v)));
            return token.equals(String.valueOf(values));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> deepCopyMap(Map<String,Object> source) throws Exception {
        Object v = deepCopyValue(source);
        return v instanceof Map<?,?> map ? new LinkedHashMap<>((Map<String,Object>) map) : new LinkedHashMap<>();
    }

    private static Object deepCopyValue(Object value) throws Exception {
        return SimpleJson.parse(SimpleJson.stringify(value));
    }

    private static List<Map<String,Object>> buildElements(Path instance, String folder, String kind) throws Exception {
        Path dir = instance.resolve(folder);
        if (!Files.isDirectory(dir)) return List.of();
        List<Map<String,Object>> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                String filename = file.getFileName().toString();
                if (folder.equals("mods") && !filename.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                if (folder.equals("resourcepacks") && !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;

                Map<String,Object> metadata = new LinkedHashMap<>();
                metadata.put("sha1", Hashing.sha1(file));
                metadata.put("size", Files.size(file));
                metadata.put("path", folder + "/" + filename);
                metadata.put("url", "");

                Map<String,Object> fileVersion = new LinkedHashMap<>();
                fileVersion.put("id", stableNegativeId(folder + ":" + filename + ":version"));
                fileVersion.put("name", filename);
                fileVersion.put("metadata", metadata);

                Map<String,Object> item = new LinkedHashMap<>();
                item.put("stateGameElement", "active");
                item.put("id", stableNegativeId(folder + ":" + filename));
                item.put("name", stripExtension(filename));
                item.put("version", fileVersion);
                item.put("userInstall", false);
                item.put("populateStatus", false);
                item.put("gameType", kind);
                out.add(item);
            }
        }
        return out;
    }

    private static long stableNegativeId(String value) {
        long h = 1125899906842597L;
        for (int i = 0; i < value.length(); i++) h = 31 * h + value.charAt(i);
        if (h == Long.MIN_VALUE) h = Long.MAX_VALUE;
        return -Math.abs(h);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
