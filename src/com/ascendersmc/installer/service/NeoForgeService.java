package com.ascendersmc.installer.service;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.*;
import com.ascendersmc.installer.util.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Instala NeoForge cuando el launcher necesita una instalación real en disco.
 *
 * CurseForge es un caso especial: minecraftinstance.json solo describe el
 * loader. El runtime real usado al pulsar Play vive bajo
 * <moddingRoot>/Install/{versions,libraries,assets}. Por eso no basta con
 * inyectar baseModLoader en la instancia.
 */
public final class NeoForgeService {
    private final InstallerConfig config;

    public NeoForgeService(InstallerConfig config) {
        this.config = config;
    }

    public void ensure(InstallerConfig.ProfileConfig profile, InstallTarget target, BiConsumer<Integer, String> progress) throws Exception {
        InstallerLogger.debug("NEOFORGE", "ensure.start | launcher=" + target.launcherType().label()
                + " | version=" + profile.neoForgeVersion() + " | gameDir=" + target.minecraftDir());

        if (target.launcherType() == LauncherType.PRISM || target.launcherType() == LauncherType.MODRINTH) {
            InstallerLogger.debug("NEOFORGE", "ensure.skip | " + target.launcherType().label()
                    + " gestionará NeoForge mediante su propio sistema de instancia");
            progress.accept(88, "NeoForge será gestionado por " + target.launcherType().label());
            return;
        }

        final boolean curseForge = target.launcherType() == LauncherType.CURSEFORGE;
        final Path root;
        if (curseForge) {
            if (target.launcherRoot() == null) {
                throw new IOException("No se pudo determinar la raíz de CurseForge para instalar NeoForge");
            }
            root = target.launcherRoot().resolve("Install").toAbsolutePath().normalize();
            InstallerLogger.debug("NEOFORGE", "curseforge.runtime-root | " + root);
        } else {
            root = (target.launcherType() == LauncherType.OFFICIAL
                    || target.launcherType() == LauncherType.TLAUNCHER
                    || target.launcherType() == LauncherType.SKLAUNCHER)
                    ? target.launcherRoot() : target.minecraftDir();
            if (root == null) throw new IOException("No se pudo determinar la raíz de instalación para NeoForge");
        }

        InstallerLogger.debug("NEOFORGE", "root=" + root);
        Files.createDirectories(root.resolve("versions"));
        Files.createDirectories(root.resolve("libraries"));
        ensureLauncherProfilesFile(root);

        String version = profile.neoForgeVersion();
        String loaderId = "neoforge-" + version;
        Path versionJson = root.resolve("versions").resolve(loaderId).resolve(loaderId + ".json");
        Path neoForgeLibraryDir = root.resolve("libraries").resolve("net").resolve("neoforged")
                .resolve("neoforge").resolve(version);

        if (runtimeReady(versionJson, neoForgeLibraryDir, curseForge)) {
            InstallerLogger.debug("NEOFORGE", "already.ready | versionJson=" + versionJson
                    + (curseForge ? " | libraryDir=" + neoForgeLibraryDir : ""));
            progress.accept(90, "NeoForge ya está instalado");
            return;
        }

        if (curseForge) {
            InstallerLogger.info("NEOFORGE", "CurseForge tiene el perfil, pero falta completar el runtime NeoForge "
                    + version + " en " + root);
            InstallerLogger.debug("NEOFORGE", "curseforge.runtime.missing | versionJson="
                    + Files.isRegularFile(versionJson) + " | libraries=" + hasRegularFiles(neoForgeLibraryDir));
        }

        String url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar";
        Path engine = config.cacheDir().resolve("neoforge");
        Files.createDirectories(engine);
        Path jar = engine.resolve("neoforge-" + version + "-installer.jar");
        String expected = readText(url + ".sha256").trim().split("\\s+")[0];
        InstallerLogger.debug("NEOFORGE", "installer.expected | jar=" + jar + " | sha256=" + expected);

        boolean validInstaller = Files.isRegularFile(jar) && Hashing.sha256(jar).equalsIgnoreCase(expected);
        InstallerLogger.debug("NEOFORGE", "installer.cache | exists=" + Files.isRegularFile(jar) + " | valid=" + validInstaller);
        if (!validInstaller) {
            Path part = jar.resolveSibling(jar.getFileName() + ".part");
            progress.accept(82, "Descargando instalador NeoForge...");
            ResilientDownloader.download(url, part, Duration.ofMinutes(10), 6, "NeoForge " + version);
            String actual = Hashing.sha256(part);
            InstallerLogger.debug("NEOFORGE", "installer.hash | expected=" + expected + " | actual=" + actual);
            if (!actual.equalsIgnoreCase(expected)) throw new SecurityException("SHA-256 incorrecto del instalador NeoForge");
            FileOps.moveReplace(part, jar);
        }

        progress.accept(86, curseForge
                ? "Preparando NeoForge para CurseForge..."
                : "Instalando NeoForge " + version + "...");
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        ProcessBuilder pb = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--install-client", root.toString());
        pb.directory(engine.toFile());
        pb.redirectErrorStream(true);
        InstallerLogger.debug("NEOFORGE", "process.start | command=" + String.join(" ", pb.command()) + " | cwd=" + engine);
        Process p = ProcessRegistry.register(pb.start(), curseForge ? "neoforge-installer-curseforge" : "neoforge-installer");
        InstallerLogger.debug("NEOFORGE", "process.pid=" + p.pid());

        Thread reader = new Thread(() -> {
            try (var r = p.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) InstallerLogger.info("NEOFORGE", line);
                }
            } catch (Exception ex) {
                InstallerLogger.debug("NEOFORGE", "output-reader.end | " + ex.getMessage());
            }
        }, "ascendersmc-neoforge-output");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!p.waitFor(15, TimeUnit.MINUTES)) {
                InstallerLogger.warn("NEOFORGE", "Timeout del instalador; terminando proceso pid=" + p.pid());
                ProcessRegistry.terminate(p, Duration.ofSeconds(4));
                throw new IOException("NeoForge agotó el tiempo máximo");
            }
            reader.join(2000);
            InstallerLogger.debug("NEOFORGE", "process.exit | code=" + p.exitValue());
            if (p.exitValue() != 0) throw new IOException("Instalador NeoForge terminó con código " + p.exitValue());
        } finally {
            ProcessRegistry.unregister(p);
        }

        if (!Files.isRegularFile(versionJson)) {
            InstallerLogger.warn("NEOFORGE", "El instalador terminó pero falta versionJson=" + versionJson);
            throw new IOException("NeoForge terminó pero no creó " + versionJson);
        }
        validateInstalledVersion(versionJson, loaderId);

        if (curseForge && !hasRegularFiles(neoForgeLibraryDir)) {
            InstallerLogger.warn("NEOFORGE", "CurseForge runtime incompleto; faltan librerías en " + neoForgeLibraryDir);
            throw new IOException("NeoForge " + version + " no instaló sus librerías dentro de CurseForge\\minecraft\\Install");
        }

        progress.accept(92, "NeoForge listo");
        InstallerLogger.debug("NEOFORGE", "ensure.end | versionJson=" + versionJson
                + (curseForge ? " | libraryDir=" + neoForgeLibraryDir : ""));
    }

    private static boolean runtimeReady(Path versionJson, Path neoForgeLibraryDir, boolean curseForge) {
        if (!Files.isRegularFile(versionJson)) return false;
        if (!curseForge) return true;
        return hasRegularFiles(neoForgeLibraryDir);
    }

    private static boolean hasRegularFiles(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (IOException ex) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateInstalledVersion(Path versionJson, String expectedId) throws Exception {
        String text = Files.readString(versionJson, StandardCharsets.UTF_8);
        Object parsed = SimpleJson.parse(text);
        if (!(parsed instanceof Map<?,?> map)) {
            throw new IOException("El version JSON instalado por NeoForge no es válido: " + versionJson);
        }
        Object id = map.get("id");
        if (id != null && !expectedId.equals(String.valueOf(id))) {
            throw new IOException("NeoForge instaló un version JSON inesperado: " + id + " (esperado " + expectedId + ")");
        }
    }

    private void ensureLauncherProfilesFile(Path root) throws Exception {
        Path profiles = root.resolve("launcher_profiles.json");
        if (Files.isRegularFile(profiles)) {
            InstallerLogger.debug("NEOFORGE", "launcher_profiles.json existe | " + profiles);
            return;
        }
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        String json = "{\n  \"profiles\": {},\n  \"selectedProfile\": \"ascendersmc\",\n  \"clientToken\": \"" + token + "\"\n}\n";
        Files.writeString(profiles, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        InstallerLogger.info("NEOFORGE", "launcher_profiles.json mínimo creado en " + root);
    }

    private String readText(String url) throws Exception {
        InstallerLogger.debug("NEOFORGE", "sha.request | " + url);
        HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                .header("User-Agent", "ASCENDERSMC-UNIVERSAL-INSTALLER/0.8.3").GET().build();
        HttpResponse<String> x = c.send(r, HttpResponse.BodyHandlers.ofString());
        InstallerLogger.debug("NEOFORGE", "sha.response | status=" + x.statusCode());
        if (x.statusCode() < 200 || x.statusCode() >= 300) throw new IOException("HTTP " + x.statusCode() + " consultando SHA-256 NeoForge");
        return x.body();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
