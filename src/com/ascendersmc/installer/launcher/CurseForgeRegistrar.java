package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.FileOps;
import com.ascendersmc.installer.util.Hashing;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.ResilientDownloader;
import com.ascendersmc.installer.util.SimpleJson;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Registro directo de perfiles custom de CurseForge.
 *
 * 0.7.2 crea perfiles directamente en Instances sin automatizar la UI, usando
 * la misma estructura de instancia custom que genera CurseForge: carpeta bajo
 * Minecraft/Instances + minecraftinstance.json completo + baseModLoader real.
 *
 * El esquema se basó en una instancia NeoForge creada manualmente por
 * CurseForge. Los JSON internos del modloader se extraen del installer oficial
 * de la versión configurada y se normalizan al formato que CurseForge utiliza.
 */
public final class CurseForgeRegistrar {
    public static final String NATIVE_MARKER = ".ascendersmc-curseforge-native";
    private static final String ZERO_DATE = "0001-01-01T00:00:00";

    private CurseForgeRegistrar() {}

    public static LauncherRegistration register(InstallerConfig config,
                                                InstallerConfig.ProfileConfig profile,
                                                InstallTarget target) throws Exception {
        Path source = target.minecraftDir().toAbsolutePath().normalize();
        Path launcherRoot = target.launcherRoot();
        if (launcherRoot == null) throw new IllegalArgumentException("CurseForge root ausente");

        Path instancesRoot = launcherRoot.resolve("Instances").toAbsolutePath().normalize();
        Files.createDirectories(instancesRoot);
        Path existing = findExistingInstance(instancesRoot, profile);

        // CurseForge mantiene la metadata de las instancias en memoria. Se
        // cierra antes de reescribir baseModLoader y se vuelve a abrir al final.
        boolean curseForgeWasRunning = isCurseForgeRunning();
        if (curseForgeWasRunning) stopCurseForge();

        try {
            NeoForgeMetadata loaderMetadata = loadNeoForgeMetadata(config, profile, launcherRoot);

            if (existing != null) {
                syncManagedContent(source, existing);
                Path icon = copyAscendersIcon(existing);
                patchExistingInstance(existing, profile, icon, loaderMetadata);
                validateNativeInstance(existing, profile);
                writeNativeMarker(existing, profile, "existing-curseforge-profile-0.7.2");
                InstallerLogger.info("LAUNCHER", "Perfil CurseForge existente reparado/actualizado: " + existing);
                return LauncherRegistration.ready("Perfil CurseForge actualizado");
            }

            Path instance = chooseInstancePath(instancesRoot, profile);
            InstallerLogger.debug("CURSEFORGE", "native-create.begin | instance=" + instance);
            Files.createDirectories(instance);
            syncManagedContent(source, instance);
            Path icon = copyAscendersIcon(instance);

            Map<String,Object> metadata = buildNativeInstance(profile, instance, icon, loaderMetadata);
            writeMetadataAtomic(instance.resolve("minecraftinstance.json"), metadata);
            validateNativeInstance(instance, profile);
            writeNativeMarker(instance, profile, "direct-custom-profile-curseforge-0.7.2");

            Files.deleteIfExists(instance.resolve("manifest.json"));
            InstallerLogger.info("LAUNCHER", "Perfil CurseForge creado directamente con metadata normalizada: " + instance);
            return LauncherRegistration.ready("Perfil CurseForge creado automáticamente");
        } finally {
            if (curseForgeWasRunning) restartCurseForge(target.launcherExecutable());
        }
    }

    private record NeoForgeMetadata(String versionJson, String installProfileJson, String clientDataSha1) {}

    @SuppressWarnings("unchecked")
    private static NeoForgeMetadata loadNeoForgeMetadata(InstallerConfig config,
                                                         InstallerConfig.ProfileConfig profile,
                                                         Path launcherRoot) throws Exception {
        String version = profile.neoForgeVersion();
        String baseUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version
                + "/neoforge-" + version + "-installer.jar";

        Path cache = config.cacheDir().resolve("neoforge");
        Files.createDirectories(cache);
        Path installer = cache.resolve("neoforge-" + version + "-installer.jar");

        String expected = readText(baseUrl + ".sha256").trim().split("\\s+")[0];
        boolean valid = Files.isRegularFile(installer)
                && Hashing.sha256(installer).equalsIgnoreCase(expected);
        InstallerLogger.debug("CURSEFORGE", "neoforge-metadata.cache | file=" + installer + " | valid=" + valid);

        if (!valid) {
            Path part = installer.resolveSibling(installer.getFileName() + ".part");
            Files.deleteIfExists(part);
            ResilientDownloader.download(baseUrl, part, Duration.ofMinutes(10), 6,
                    "NeoForge metadata " + version);
            String actual = Hashing.sha256(part);
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(part);
                throw new SecurityException("SHA-256 incorrecto del instalador NeoForge " + version);
            }
            FileOps.moveReplace(part, installer);
        }

        try (ZipFile zip = new ZipFile(installer.toFile())) {
            Object rawVersion = SimpleJson.parse(readZipText(zip, "version.json"));
            Object rawInstall = SimpleJson.parse(readZipText(zip, "install_profile.json"));
            if (!(rawVersion instanceof Map<?,?>) || !(rawInstall instanceof Map<?,?>)) {
                throw new IOException("Metadata NeoForge inválida dentro del installer");
            }

            Map<String,Object> versionDoc = new LinkedHashMap<>((Map<String,Object>) rawVersion);
            Map<String,Object> installDoc = new LinkedHashMap<>((Map<String,Object>) rawInstall);
            normalizeCurseForgeVersionJson(versionDoc);

            byte[] clientData = readZipBytes(zip, "data/client.lzma");
            String clientSha1 = sha1(clientData);
            normalizeCurseForgeInstallProfile(installDoc, profile.minecraftVersion(), version, clientSha1);
            stageCurseForgeClientData(launcherRoot, version, clientData, clientSha1);

            String versionJson = SimpleJson.stringify(versionDoc);
            String installProfileJson = SimpleJson.stringify(installDoc);
            InstallerLogger.debug("CURSEFORGE", "neoforge-metadata.normalized | version=" + version
                    + " | versionJsonBytes=" + versionJson.getBytes(StandardCharsets.UTF_8).length
                    + " | installProfileBytes=" + installProfileJson.getBytes(StandardCharsets.UTF_8).length
                    + " | clientDataSha1=" + clientSha1);
            return new NeoForgeMetadata(versionJson, installProfileJson, clientSha1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void normalizeCurseForgeVersionJson(Map<String,Object> doc) {
        Object libsObj = doc.get("libraries");
        if (!(libsObj instanceof List<?> libs)) return;
        for (Object item : libs) {
            if (!(item instanceof Map<?,?> raw)) continue;
            Map<String,Object> lib = (Map<String,Object>) raw;
            // Así aparecen las librerías en versionJson generado por CurseForge.
            lib.putIfAbsent("url", null);
            lib.putIfAbsent("serverreq", null);
            lib.putIfAbsent("clientreq", null);
            lib.putIfAbsent("natives", null);
            lib.putIfAbsent("extract", null);
            lib.putIfAbsent("rules", null);

            Object downloadsObj = lib.get("downloads");
            if (downloadsObj instanceof Map<?,?> downloadsRaw) {
                Map<String,Object> downloads = (Map<String,Object>) downloadsRaw;
                downloads.putIfAbsent("classifiers", null);
                Object artifactObj = downloads.get("artifact");
                if (artifactObj instanceof Map<?,?> artifactRaw) {
                    Map<String,Object> artifact = (Map<String,Object>) artifactRaw;
                    Object urlObj = artifact.get("url");
                    if (urlObj instanceof String url && url.startsWith("https://maven.neoforged.net/releases/")) {
                        artifact.put("url", "https://neoforged.forgecdn.net/releases/"
                                + url.substring("https://maven.neoforged.net/releases/".length()));
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void normalizeCurseForgeInstallProfile(Map<String,Object> doc,
                                                           String minecraftVersion,
                                                           String version,
                                                           String clientSha1) throws IOException {
        Object libsObj = doc.get("libraries");
        List<Object> libs;
        if (libsObj instanceof List<?> raw) {
            libs = (List<Object>) raw;
        } else {
            libs = new ArrayList<>();
            doc.put("libraries", libs);
        }

        // CurseForge no consume install_profile.json exactamente como viene en
        // el JAR de NeoForge. Su NeoForgeInstallerProvider espera la estructura
        // de data/processors que devuelve su endpoint de modloaders. Los logs de
        // un perfil 21.1.248 creado por CurseForge confirman este flujo de seis
        // procesadores (MCP_DATA, DOWNLOAD_MOJMAPS, MERGE_MAPPING, jarsplitter,
        // ART y binarypatcher). Lo reproducimos dinámicamente aquí.
        String neoFormVersion = findNeoFormVersion(libs);
        if (neoFormVersion == null || neoFormVersion.isBlank()) {
            throw new IOException("No se pudo determinar la versión NeoForm desde install_profile.json");
        }

        doc.put("profile", "NeoForge");
        doc.put("version", "neoforge-" + version);
        doc.put("minecraft", minecraftVersion);
        doc.put("json", "/version.json");
        doc.put("path", "net.neoforged:neoforge:" + version);
        doc.put("data", buildCurseForgeInstallData(minecraftVersion, neoFormVersion, version));
        doc.put("processors", buildCurseForgeProcessors(minecraftVersion, neoFormVersion));
        doc.put("serverJarPath", "{LIBRARY_DIR}/net/minecraft/server/{MINECRAFT_VERSION}/server-{MINECRAFT_VERSION}.jar");

        String clientName = "net.neoforged:neoforge:" + version + ":clientdata@lzma";
        String clientPath = "net/neoforged/neoforge/" + version + "/neoforge-" + version + "-clientdata.lzma";
        boolean clientPresent = false;
        boolean universalPresent = false;

        for (Object item : libs) {
            if (!(item instanceof Map<?,?> raw)) continue;
            Map<String,Object> lib = (Map<String,Object>) raw;
            lib.put("url", null);
            String name = String.valueOf(lib.get("name"));
            if (clientName.equals(name)) {
                clientPresent = true;
                lib.put("downloads", clientDataDownloads(version, clientPath, clientSha1));
            }
            if (("net.neoforged:neoforge:" + version + ":universal").equals(name)) {
                universalPresent = true;
            }
        }

        if (!clientPresent) {
            Map<String,Object> client = new LinkedHashMap<>();
            client.put("name", clientName);
            client.put("url", null);
            client.put("downloads", clientDataDownloads(version, clientPath, clientSha1));
            libs.add(client);
        }
        if (!universalPresent) {
            throw new IOException("installProfileJson no contiene el universal de NeoForge " + version);
        }

        auditInstallProfileTokens(doc);
        InstallerLogger.debug("CURSEFORGE", "install-profile.rebuilt | minecraft=" + minecraftVersion
                + " | neoforge=" + version + " | neoform=" + neoFormVersion
                + " | processors=" + ((List<?>) doc.get("processors")).size());
    }

    private static String findNeoFormVersion(List<Object> libs) {
        String prefix = "net.neoforged:neoform:";
        for (Object item : libs) {
            if (!(item instanceof Map<?,?> lib)) continue;
            Object n = lib.get("name");
            if (!(n instanceof String name) || !name.startsWith(prefix)) continue;
            String rest = name.substring(prefix.length());
            int classifier = rest.indexOf(':');
            if (classifier >= 0) rest = rest.substring(0, classifier);
            int at = rest.indexOf('@');
            if (at >= 0) rest = rest.substring(0, at);
            if (!rest.isBlank()) return rest;
        }
        return null;
    }

    private static Map<String,Object> buildCurseForgeInstallData(String minecraftVersion,
                                                                  String neoFormVersion,
                                                                  String neoForgeVersion) {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("MAPPINGS", sided(
                "[net.neoforged:neoform:" + neoFormVersion + ":mappings@txt]",
                "[net.neoforged:neoform:" + neoFormVersion + ":mappings@txt]"));
        data.put("MOJMAPS", sided(
                "[net.minecraft:client:" + neoFormVersion + ":mappings@txt]",
                "[net.minecraft:server:" + neoFormVersion + ":mappings@txt]"));
        data.put("MERGED_MAPPINGS", sided(
                "[net.neoforged:neoform:" + neoFormVersion + ":mappings-merged@txt]",
                "[net.neoforged:neoform:" + neoFormVersion + ":mappings-merged@txt]"));
        data.put("BINPATCH", sided("/data/client.lzma", "/data/server.lzma"));
        data.put("MC_UNPACKED", sided(
                "[net.minecraft:client:" + neoFormVersion + ":unpacked]",
                "[net.minecraft:server:" + neoFormVersion + ":unpacked]"));
        data.put("MC_SLIM", sided(
                "[net.minecraft:client:" + neoFormVersion + ":slim]",
                "[net.minecraft:server:" + neoFormVersion + ":slim]"));
        data.put("MC_EXTRA", sided(
                "[net.minecraft:client:" + neoFormVersion + ":extra]",
                "[net.minecraft:server:" + neoFormVersion + ":extra]"));
        data.put("MC_SRG", sided(
                "[net.minecraft:client:" + neoFormVersion + ":srg]",
                "[net.minecraft:server:" + neoFormVersion + ":srg]"));
        data.put("PATCHED", sided(
                "[net.neoforged:neoforge:" + neoForgeVersion + ":client]",
                "[net.neoforged:neoforge:" + neoForgeVersion + ":server]"));
        data.put("MCP_VERSION", sided("'" + neoFormVersion + "'", "'" + neoFormVersion + "'"));
        data.put("SIDE", sided("client", "server"));
        return data;
    }

    private static Map<String,Object> sided(String client, String server) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("client", client);
        value.put("server", server);
        return value;
    }

    private static List<Object> buildCurseForgeProcessors(String minecraftVersion,
                                                           String neoFormVersion) {
        List<Object> processors = new ArrayList<>();

        List<String> installerToolsClasspath = List.of(
                "net.neoforged.installertools:installertools:2.1.2",
                "net.neoforged:srgutils:1.0.0",
                "net.md-5:SpecialSource:1.11.0",
                "net.sf.jopt-simple:jopt-simple:5.0.4",
                "com.google.code.gson:gson:2.8.9",
                "de.siegmar:fastcsv:2.0.0",
                "org.ow2.asm:asm-commons:9.3",
                "net.neoforged.installertools:cli-utils:2.1.2",
                "com.google.guava:guava:20.0",
                "com.opencsv:opencsv:4.4",
                "org.ow2.asm:asm-analysis:9.3",
                "org.ow2.asm:asm-tree:9.3",
                "org.ow2.asm:asm:9.3",
                "org.apache.commons:commons-text:1.3",
                "org.apache.commons:commons-lang3:3.8.1",
                "commons-beanutils:commons-beanutils:1.9.3",
                "org.apache.commons:commons-collections4:4.2",
                "commons-logging:commons-logging:1.2",
                "commons-collections:commons-collections:3.2.2");

        processors.add(processor("net.neoforged.installertools:installertools:2.1.2",
                installerToolsClasspath,
                List.of("--task", "MCP_DATA", "--input",
                        "[net.neoforged:neoform:" + neoFormVersion + "@zip]",
                        "--output", "{MAPPINGS}", "--key", "mappings"), null));

        processors.add(processor("net.neoforged.installertools:installertools:2.1.2",
                installerToolsClasspath,
                List.of("--task", "DOWNLOAD_MOJMAPS", "--version", minecraftVersion,
                        "--side", "{SIDE}", "--output", "{MOJMAPS}"), null));

        processors.add(processor("net.neoforged.installertools:installertools:2.1.2",
                installerToolsClasspath,
                List.of("--task", "MERGE_MAPPING", "--left", "{MAPPINGS}",
                        "--right", "{MOJMAPS}", "--output", "{MERGED_MAPPINGS}",
                        "--classes", "--fields", "--methods", "--reverse-right"), null));

        processors.add(processor("net.neoforged.installertools:jarsplitter:2.1.2",
                List.of("net.neoforged.installertools:jarsplitter:2.1.2",
                        "net.sf.jopt-simple:jopt-simple:5.0.4",
                        "net.neoforged:srgutils:1.0.0",
                        "net.neoforged.installertools:cli-utils:2.1.2"),
                List.of("--input", "{MINECRAFT_JAR}", "--slim", "{MC_SLIM}",
                        "--extra", "{MC_EXTRA}", "--srg", "{MERGED_MAPPINGS}"),
                List.of("client")));

        processors.add(processor("net.neoforged:AutoRenamingTool:2.0.3:all",
                List.of("net.neoforged:AutoRenamingTool:2.0.3:all"),
                List.of("--input", "{MC_SLIM}", "--output", "{MC_SRG}",
                        "--names", "{MERGED_MAPPINGS}", "--ann-fix", "--ids-fix",
                        "--src-fix", "--record-fix"), null));

        processors.add(processor("net.neoforged.installertools:binarypatcher:2.1.2:fatjar",
                List.of("net.neoforged.installertools:binarypatcher:2.1.2:fatjar"),
                List.of("--clean", "{MC_SRG}", "--output", "{PATCHED}",
                        "--apply", "{BINPATCH}"), null));

        return processors;
    }

    private static Map<String,Object> processor(String jar,
                                                List<String> classpath,
                                                List<String> args,
                                                List<String> sides) {
        Map<String,Object> p = new LinkedHashMap<>();
        p.put("jar", jar);
        p.put("classpath", new ArrayList<>(classpath));
        p.put("args", new ArrayList<>(args));
        p.put("sides", sides == null ? null : new ArrayList<>(sides));
        return p;
    }

    @SuppressWarnings("unchecked")
    private static void auditInstallProfileTokens(Map<String,Object> doc) throws IOException {
        Object dataObj = doc.get("data");
        if (!(dataObj instanceof Map<?,?> data)) {
            throw new IOException("installProfileJson no contiene data");
        }
        Set<String> keys = new TreeSet<>();
        for (Object key : data.keySet()) keys.add(String.valueOf(key));

        Set<String> used = new TreeSet<>();
        collectTokens(doc.get("processors"), used);
        collectTokens(doc.get("serverJarPath"), used);

        Set<String> builtins = Set.of("MINECRAFT_JAR", "LIBRARY_DIR", "MINECRAFT_VERSION");
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(keys);
        missing.removeAll(builtins);

        InstallerLogger.debug("CURSEFORGE", "install-profile.tokens | used=" + used
                + " | data=" + keys + " | builtins=" + builtins + " | missing=" + missing);
        if (!missing.isEmpty()) {
            throw new IOException("installProfileJson contiene tokens sin resolver: " + missing);
        }
    }

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([A-Z0-9_]+)\\}");

    private static void collectTokens(Object value, Set<String> out) {
        if (value == null) return;
        if (value instanceof String s) {
            Matcher m = TOKEN_PATTERN.matcher(s);
            while (m.find()) out.add(m.group(1));
            return;
        }
        if (value instanceof Map<?,?> map) {
            for (Object v : map.values()) collectTokens(v, out);
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object v : values) collectTokens(v, out);
        }
    }

    private static Map<String,Object> clientDataDownloads(String version, String path, String sha1) {
        Map<String,Object> artifact = new LinkedHashMap<>();
        artifact.put("sha1", sha1);
        // CurseForge guarda size=0 para clientdata@lzma en perfiles NeoForge.
        artifact.put("size", 0);
        artifact.put("url", "https://modloaders.forgecdn.net/647622546/maven/" + path);
        artifact.put("path", path);
        Map<String,Object> downloads = new LinkedHashMap<>();
        downloads.put("artifact", artifact);
        return downloads;
    }

    private static void stageCurseForgeClientData(Path launcherRoot,
                                                   String version,
                                                   byte[] data,
                                                   String expectedSha1) throws Exception {
        Path target = launcherRoot.resolve("Install").resolve("libraries")
                .resolve("net").resolve("neoforged").resolve("neoforge").resolve(version)
                .resolve("neoforge-" + version + "-clientdata.lzma");
        Files.createDirectories(target.getParent());
        if (!Files.isRegularFile(target) || !expectedSha1.equalsIgnoreCase(sha1(Files.readAllBytes(target)))) {
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.write(part, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            FileOps.moveReplace(part, target);
        }
        String actual = sha1(Files.readAllBytes(target));
        if (!expectedSha1.equalsIgnoreCase(actual)) {
            throw new IOException("clientdata.lzma de NeoForge quedó corrupto en runtime CurseForge");
        }
        InstallerLogger.debug("CURSEFORGE", "clientdata.ready | file=" + target
                + " | bytes=" + Files.size(target) + " | sha1=" + actual);
    }

    private static byte[] readZipBytes(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new FileNotFoundException(name + " no existe dentro del installer NeoForge");
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static String readZipText(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new FileNotFoundException(name + " no existe dentro del installer NeoForge");
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readText(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "ASCENDERSMC-UNIVERSAL-INSTALLER/0.8.4")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " consultando metadata NeoForge");
        }
        return response.body();
    }

    private static Map<String,Object> buildBaseModLoader(InstallerConfig.ProfileConfig profile,
                                                         NeoForgeMetadata neoForge) {
        String loaderName = "neoforge-" + profile.neoForgeVersion();
        Map<String,Object> baseModLoader = new LinkedHashMap<>();
        baseModLoader.put("forgeVersion", profile.neoForgeVersion());
        baseModLoader.put("name", loaderName);
        baseModLoader.put("type", 6);
        baseModLoader.put("downloadUrl", "");
        baseModLoader.put("filename", loaderName + ".jar");
        baseModLoader.put("installMethod", 6);
        baseModLoader.put("latest", true);
        baseModLoader.put("recommended", true);
        baseModLoader.put("versionJson", neoForge.versionJson());
        baseModLoader.put("librariesInstallLocation", "{0}//libraries//net//neoforged//neoforge//" + profile.neoForgeVersion());
        baseModLoader.put("minecraftVersion", profile.minecraftVersion());
        baseModLoader.put("installProfileJson", neoForge.installProfileJson());
        return baseModLoader;
    }

    private static Map<String,Object> buildNativeInstance(InstallerConfig.ProfileConfig profile,
                                                          Path instance,
                                                          Path icon,
                                                          NeoForgeMetadata neoForge) {
        String now = Instant.now().toString();
        String loaderName = "neoforge-" + profile.neoForgeVersion();
        String guid = UUID.randomUUID().toString();

        Map<String,Object> baseModLoader = buildBaseModLoader(profile, neoForge);

        Map<String,Object> syncProfile = new LinkedHashMap<>();
        syncProfile.put("PreferenceEnabled", false);
        syncProfile.put("PreferenceAutoSync", true);
        syncProfile.put("PreferenceAutoDelete", false);
        syncProfile.put("PreferenceBackupSavedVariables", false);
        syncProfile.put("GameInstanceGuid", "00000000-0000-0000-0000-000000000000");
        syncProfile.put("SyncProfileID", 0);
        syncProfile.put("SavedVariablesProfile", null);
        syncProfile.put("LastSyncDate", ZERO_DATE);

        Map<String,Object> doc = new LinkedHashMap<>();
        doc.put("baseModLoader", baseModLoader);
        doc.put("isUnlocked", true);
        doc.put("javaArgsOverride", null);
        doc.put("lastPlayed", ZERO_DATE);
        doc.put("playedCount", 0);
        doc.put("timePlayed", 0);
        doc.put("manifest", null);
        doc.put("fileDate", ZERO_DATE);
        doc.put("installedModpack", null);
        doc.put("projectID", 0);
        doc.put("fileID", 0);
        doc.put("customAuthor", null);
        doc.put("modpackOverrides", new ArrayList<>());
        doc.put("isMemoryOverride", false);
        doc.put("allocatedMemory", 4096);
        doc.put("profileImagePath", icon == null ? null : icon.toAbsolutePath().normalize().toString());
        doc.put("groupId", null);
        doc.put("isVanilla", false);
        doc.put("guid", guid);
        doc.put("gameTypeID", 432);
        doc.put("installPath", withTrailingSeparator(instance));
        doc.put("name", "ASCENDERSMC " + profile.profile().displayName());
        doc.put("cachedScans", new ArrayList<>());
        doc.put("isValid", true);
        doc.put("lastPreviousMatchUpdate", ZERO_DATE);
        doc.put("lastRefreshAttempt", now);
        doc.put("isEnabled", true);
        doc.put("gameVersion", profile.minecraftVersion());
        doc.put("gameVersionFlavor", null);
        doc.put("gameVersionTypeId", null);
        doc.put("preferenceAlternateFile", false);
        doc.put("preferenceAutoInstallUpdates", false);
        doc.put("preferenceDeleteOrphanedDependencies", false);
        doc.put("preferenceDeleteSavedVariables", false);
        doc.put("preferenceReleaseType", 1);
        doc.put("preferenceModdingFolderPath", null);
        doc.put("syncProfile", syncProfile);
        doc.put("installDate", now);
        // Los JAR de ASCENDERSMC son administrados por nuestro updater. Un
        // perfil custom recién creado puede tener esta lista vacía; isUnlocked
        // permite a CurseForge ejecutar mods presentes físicamente en /mods.
        doc.put("installedAddons", new ArrayList<>());
        doc.put("installedGamePrerequisites", new ArrayList<>());
        doc.put("wasNameManuallyChanged", false);
        doc.put("wasGameVersionTypeIdManuallyChanged", false);
        return doc;
    }

    private static void patchExistingInstance(Path instance,
                                              InstallerConfig.ProfileConfig profile,
                                              Path icon,
                                              NeoForgeMetadata neoForge) {
        Path metadataFile = instance.resolve("minecraftinstance.json");
        if (!Files.isRegularFile(metadataFile)) return;
        try {
            Map<String,Object> doc = readJsonMap(metadataFile);
            if (doc == null) return;
            doc.put("name", "ASCENDERSMC " + profile.profile().displayName());
            doc.put("installPath", withTrailingSeparator(instance));
            doc.put("gameVersion", profile.minecraftVersion());
            doc.put("isUnlocked", true);
            doc.put("isValid", true);
            doc.put("isEnabled", true);
            doc.put("baseModLoader", buildBaseModLoader(profile, neoForge));
            if (icon != null) doc.put("profileImagePath", icon.toAbsolutePath().normalize().toString());
            writeMetadataAtomic(metadataFile, doc);
            InstallerLogger.debug("CURSEFORGE", "existing.metadata.patched | " + metadataFile);
        } catch (Exception ex) {
            InstallerLogger.warn("CURSEFORGE", "No se pudo actualizar metadata existente: " + ex.getMessage());
        }
    }

    private static void validateNativeInstance(Path instance,
                                               InstallerConfig.ProfileConfig profile) throws Exception {
        Path file = instance.resolve("minecraftinstance.json");
        Map<String,Object> doc = readJsonMap(file);
        if (doc == null) throw new IOException("minecraftinstance.json no se pudo volver a leer");
        if (!Objects.equals(String.valueOf(doc.get("gameVersion")), profile.minecraftVersion())) {
            throw new IOException("minecraftinstance.json tiene gameVersion incorrecta");
        }
        if (!numericEquals(doc.get("gameTypeID"), 432L)) {
            throw new IOException("minecraftinstance.json no tiene gameTypeID=432 (valor="
                    + doc.get("gameTypeID") + ", tipo=" + typeName(doc.get("gameTypeID")) + ")");
        }
        if (doc.get("manifest") != null || doc.get("installedModpack") != null) {
            throw new IOException("El perfil CurseForge generado no quedó como custom profile");
        }
        Object loaderObj = doc.get("baseModLoader");
        if (!(loaderObj instanceof Map<?,?> loader)) {
            throw new IOException("Falta baseModLoader en minecraftinstance.json");
        }
        String expectedName = "neoforge-" + profile.neoForgeVersion();
        if (!expectedName.equals(String.valueOf(loader.get("name")))) {
            throw new IOException("baseModLoader.name incorrecto");
        }
        if (!(loader.get("versionJson") instanceof String versionJson) || versionJson.isBlank()
                || !(loader.get("installProfileJson") instanceof String installProfileJson) || installProfileJson.isBlank()) {
            throw new IOException("Faltan versionJson/installProfileJson de NeoForge");
        }
        validateNormalizedLoaderJson(versionJson, installProfileJson, profile.neoForgeVersion());
        InstallerLogger.debug("CURSEFORGE", "native-create.validate.ok | metadata=" + file
                + " | loader=" + expectedName + " | guid=" + doc.get("guid"));
    }

    @SuppressWarnings("unchecked")
    private static void validateNormalizedLoaderJson(String versionJson,
                                                     String installProfileJson,
                                                     String version) throws Exception {
        Object versionParsed = SimpleJson.parse(versionJson);
        Object installParsed = SimpleJson.parse(installProfileJson);
        if (!(versionParsed instanceof Map<?,?> versionMap) || !(installParsed instanceof Map<?,?> installMap)) {
            throw new IOException("baseModLoader contiene JSON interno inválido");
        }
        if (!String.valueOf(versionMap.get("id")).equals("neoforge-" + version)) {
            throw new IOException("versionJson.id no corresponde a NeoForge " + version);
        }
        Object libsObj = installMap.get("libraries");
        if (!(libsObj instanceof List<?> libs)) throw new IOException("installProfileJson no contiene libraries");
        String expected = "net.neoforged:neoforge:" + version + ":clientdata@lzma";
        boolean found = false;
        for (Object x : libs) {
            if (x instanceof Map<?,?> lib && expected.equals(String.valueOf(lib.get("name")))) {
                found = true;
                break;
            }
        }
        if (!found) throw new IOException("installProfileJson no contiene clientdata@lzma de CurseForge");

        @SuppressWarnings("unchecked")
        Map<String,Object> normalizedInstall = (Map<String,Object>) installMap;
        auditInstallProfileTokens(normalizedInstall);
        Object processorsObj = normalizedInstall.get("processors");
        if (!(processorsObj instanceof List<?> processors) || processors.size() != 6) {
            throw new IOException("installProfileJson no contiene los 6 procesadores NeoForge esperados");
        }
        Object dataObj = normalizedInstall.get("data");
        if (!(dataObj instanceof Map<?,?> data)) {
            throw new IOException("installProfileJson no contiene data de NeoForge");
        }
        for (String required : List.of("MAPPINGS", "MOJMAPS", "MERGED_MAPPINGS", "BINPATCH",
                "MC_UNPACKED", "MC_SLIM", "MC_EXTRA", "MC_SRG", "PATCHED", "MCP_VERSION", "SIDE")) {
            if (!data.containsKey(required)) {
                throw new IOException("installProfileJson no contiene data." + required);
            }
        }
    }

    private static Path findExistingInstance(Path instancesRoot,
                                             InstallerConfig.ProfileConfig profile) {
        if (!Files.isDirectory(instancesRoot)) return null;
        String display = profile.profile().displayName().toLowerCase(Locale.ROOT);
        try (var stream = Files.list(instancesRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path metadata = dir.resolve("minecraftinstance.json");
                if (!Files.isRegularFile(metadata)) continue;
                Map<String,Object> doc = readJsonMap(metadata);
                if (doc == null) continue;

                String name = String.valueOf(doc.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
                String pathName = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                String gameVersion = String.valueOf(doc.getOrDefault("gameVersion", ""));
                boolean ascenders = name.contains("ascendersmc") || pathName.contains("ascendersmc");
                boolean profileMatch = name.contains(display) || pathName.contains(display);
                if (ascenders && profileMatch && profile.minecraftVersion().equals(gameVersion)
                        && looksLikeNativeCustomProfile(doc)) {
                    InstallerLogger.debug("CURSEFORGE", "native-existing.detected | " + dir);
                    return dir;
                }
                if (ascenders && profileMatch) {
                    InstallerLogger.debug("CURSEFORGE", "legacy-or-invalid-instance.skip | " + dir);
                }
            }
        } catch (Exception ex) {
            InstallerLogger.debug("CURSEFORGE", "native-existing.scan.failed | " + ex.getMessage());
        }
        return null;
    }


    private static boolean looksLikeNativeCustomProfile(Map<String,Object> doc) {
        if (doc == null) return false;
        if (doc.get("manifest") != null || doc.get("installedModpack") != null) return false;
        if (!numericEquals(doc.get("gameTypeID"), 432L)) return false;
        if (!numericEquals(doc.getOrDefault("projectID", 0), 0L)) return false;
        if (!numericEquals(doc.getOrDefault("fileID", 0), 0L)) return false;
        Object loaderObj = doc.get("baseModLoader");
        if (!(loaderObj instanceof Map<?,?> loader)) return false;
        String name = String.valueOf(loader.get("name") == null ? "" : loader.get("name")).toLowerCase(Locale.ROOT);
        String filename = String.valueOf(loader.get("filename") == null ? "" : loader.get("filename")).toLowerCase(Locale.ROOT);
        String versionJson = String.valueOf(loader.get("versionJson") == null ? "" : loader.get("versionJson"));
        String installProfileJson = String.valueOf(loader.get("installProfileJson") == null ? "" : loader.get("installProfileJson"));
        return name.startsWith("neoforge-")
                && filename.startsWith("neoforge-")
                && !filename.contains("-installer")
                && !versionJson.isBlank()
                && !installProfileJson.isBlank();
    }

    private static boolean numericEquals(Object value, long expected) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue() == expected;
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) && d == (double) expected;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim()) == expected;
            } catch (NumberFormatException ignored) {
                try {
                    double d = Double.parseDouble(text.trim());
                    return Double.isFinite(d) && d == (double) expected;
                } catch (NumberFormatException ignoredAgain) {
                    return false;
                }
            }
        }
        return false;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static Path chooseInstancePath(Path instancesRoot,
                                           InstallerConfig.ProfileConfig profile) throws IOException {
        Path preferred = instancesRoot.resolve("ASCENDERSMC-" + profile.profile().displayName())
                .toAbsolutePath().normalize();
        if (!Files.exists(preferred)) return preferred;

        // Si la carpeta viene de un intento viejo de ASCENDERSMC y todavía no
        // es una instancia real, se reemplaza. No se borra una carpeta ajena.
        boolean managedLegacy = Files.isRegularFile(preferred.resolve(NATIVE_MARKER))
                || Files.isRegularFile(preferred.resolve("ASCENDERSMC-PROFILE.txt"));
        if (managedLegacy) {
            deleteTree(preferred);
            InstallerLogger.debug("CURSEFORGE", "legacy-managed-folder.replaced | " + preferred);
            return preferred;
        }

        int suffix = 2;
        Path candidate;
        do {
            candidate = instancesRoot.resolve("ASCENDERSMC-" + profile.profile().displayName() + "-" + suffix++);
        } while (Files.exists(candidate));
        return candidate.toAbsolutePath().normalize();
    }

    private static void syncManagedContent(Path source, Path instance) throws IOException {
        if (source == null || instance == null) return;
        source = source.toAbsolutePath().normalize();
        instance = instance.toAbsolutePath().normalize();
        if (source.equals(instance)) return;
        Files.createDirectories(instance);

        for (String folder : List.of("mods", "resourcepacks", "config", "defaultconfigs", "shaderpacks")) {
            Path from = source.resolve(folder);
            if (!Files.isDirectory(from)) continue;
            Path to = instance.resolve(folder);
            deleteTree(to);
            copyTree(from, to);
        }
        for (String file : List.of("options.txt", "servers.dat", "servers.dat_old", "ASCENDERSMC-PROFILE.txt")) {
            Path from = source.resolve(file);
            if (Files.isRegularFile(from)) {
                Files.copy(from, instance.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        long jars = 0;
        Path mods = instance.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var st = Files.list(mods)) {
                jars = st.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .count();
            }
        }
        InstallerLogger.debug("CURSEFORGE", "native-instance.sync | source=" + source
                + " | target=" + instance + " | jars=" + jars);
    }

    private static Path copyAscendersIcon(Path instance) {
        Path primary = instance.resolve("profileImage.png");
        try (InputStream in = CurseForgeRegistrar.class.getResourceAsStream("/assets/logo.png")) {
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            Files.write(primary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(instance.resolve("icon.png"), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            InstallerLogger.debug("CURSEFORGE", "icon.write | primary=" + primary);
            return primary;
        } catch (Exception ex) {
            InstallerLogger.debug("CURSEFORGE", "icon.copy.failed | " + ex.getMessage());
            return null;
        }
    }

    private static void writeNativeMarker(Path instance,
                                          InstallerConfig.ProfileConfig profile,
                                          String method) {
        try {
            String body = "profile=" + profile.profile().name()
                    + "\nmethod=" + method
                    + "\nschema=curseforge-custom-profile"
                    + "\nregistered=" + Instant.now() + "\n";
            Files.writeString(instance.resolve(NATIVE_MARKER), body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ex) {
            InstallerLogger.debug("CURSEFORGE", "native.marker.failed | " + ex.getMessage());
        }
    }

    private static void writeMetadataAtomic(Path file, Map<String,Object> metadata) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(tmp, SimpleJson.stringify(metadata), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> readJsonMap(Path file) {
        try {
            Object parsed = SimpleJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            return parsed instanceof Map<?,?> map
                    ? new LinkedHashMap<>((Map<String,Object>) map)
                    : null;
        } catch (Exception ex) {
            InstallerLogger.debug("CURSEFORGE", "metadata.read.failed | file=" + file + " | " + ex.getMessage());
            return null;
        }
    }

    private static boolean isCurseForgeRunning() {
        if (!isWindows()) return false;
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "if(Get-Process -Name CurseForge -ErrorAction SilentlyContinue){exit 0}else{exit 1}")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void stopCurseForge() {
        if (!isWindows()) return;
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "$p=Get-Process -Name CurseForge -ErrorAction SilentlyContinue; "
                            + "if($p){$p|ForEach-Object{$_.CloseMainWindow()|Out-Null}; Start-Sleep -Milliseconds 1800; "
                            + "$p=Get-Process -Name CurseForge -ErrorAction SilentlyContinue; if($p){$p|Stop-Process -Force}}")
                    .redirectErrorStream(true).start();
            process.waitFor();
            InstallerLogger.debug("CURSEFORGE", "rescan.stop | proceso CurseForge cerrado antes de escribir metadata");
        } catch (Exception ex) {
            InstallerLogger.debug("CURSEFORGE", "rescan.stop.failed | " + ex.getMessage());
        }
    }

    private static void restartCurseForge(Path launcherEntry) {
        if (!isWindows()) return;
        try {
            Path entry = resolveLaunchEntry(launcherEntry);
            if (entry == null || !Files.exists(entry)) {
                InstallerLogger.debug("CURSEFORGE", "rescan.restart.skip | launcherEntry=" + launcherEntry);
                return;
            }
            new ProcessBuilder("cmd.exe", "/c", "start", "", entry.toString()).start();
            InstallerLogger.debug("CURSEFORGE", "rescan.restart | " + entry);
        } catch (Exception ex) {
            InstallerLogger.debug("CURSEFORGE", "rescan.restart.failed | " + ex.getMessage());
        }
    }

    private static Path resolveLaunchEntry(Path launcherEntry) {
        if (launcherEntry != null && Files.exists(launcherEntry)) return launcherEntry;
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            Path direct = Path.of(local, "Programs", "CurseForge Windows", "CurseForge.exe");
            if (Files.isRegularFile(direct)) return direct;
            direct = Path.of(local, "Programs", "CurseForge", "CurseForge.exe");
            if (Files.isRegularFile(direct)) return direct;
        }
        return launcherEntry;
    }

    private static String withTrailingSeparator(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return value.endsWith(File.separator) ? value : value + File.separator;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path p : stream.toList()) {
                Path rel = source.relativize(p);
                Path out = target.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(out);
                else Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
