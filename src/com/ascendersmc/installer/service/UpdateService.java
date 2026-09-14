package com.ascendersmc.installer.service;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.util.FileOps;
import com.ascendersmc.installer.util.Hashing;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.ResilientDownloader;
import com.ascendersmc.installer.util.SimpleJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Auto-updater del propio ASCENDERSMC UNIVERSAL INSTALLER.
 *
 * La fuente de verdad es GitHub Releases. Discord solo anuncia publicaciones;
 * nunca participa en la cadena de actualización. Cada JAR descargado se valida
 * contra SHA256SUMS.txt antes de ser ejecutado o sustituir el archivo actual.
 */
public final class UpdateService {
    public static final String APPLY_UPDATE_ARG = "--apply-update";
    private static final Duration API_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    public enum UpdateState {
        IDLE("Sin actualización en curso"),
        CHECKING("Comprobando actualizaciones"),
        DOWNLOADING("Descargando una actualización"),
        READY_WAITING("Actualización descargada; esperando un momento seguro para reiniciar"),
        APPLYING("Aplicando la actualización del Installer");

        private final String displayName;
        UpdateState(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
        public boolean isBusy() { return this != IDLE; }
        public boolean isCritical() { return this == DOWNLOADING || this == READY_WAITING || this == APPLYING; }
    }

    private static volatile UpdateState state = UpdateState.IDLE;

    private UpdateService() {}

    public static UpdateState state() { return state; }
    private static void setState(UpdateState next) {
        state = next == null ? UpdateState.IDLE : next;
        InstallerLogger.debug("UPDATE", "state=" + state);
    }

    /**
     * Modo helper: se ejecuta desde el JAR nuevo, espera a que termine el proceso
     * anterior, reemplaza el JAR original y lo vuelve a iniciar.
     */
    public static boolean handleApplyMode(String[] args) {
        if (args == null || args.length == 0 || !APPLY_UPDATE_ARG.equals(args[0])) return false;
        if (args.length < 3) {
            System.err.println("Uso interno inválido de --apply-update");
            return true;
        }

        try {
            setState(UpdateState.APPLYING);
            Path targetJar = Path.of(args[1]).toAbsolutePath().normalize();
            long parentPid = Long.parseLong(args[2]);
            Path helperJar = currentJarPath().orElseThrow(() -> new IOException("No se pudo localizar el JAR helper"));

            waitForProcessExit(parentPid, Duration.ofSeconds(90));
            Files.createDirectories(targetJar.getParent());

            Path replacement = targetJar.resolveSibling(targetJar.getFileName() + ".update");
            Files.copy(helperJar, replacement, StandardCopyOption.REPLACE_EXISTING);
            FileOps.moveReplace(replacement, targetJar);

            String java = javaExecutable().toString();
            new ProcessBuilder(java, "-jar", targetJar.toString())
                    .directory(targetJar.getParent().toFile())
                    .start();
        } catch (Exception ex) {
            // Si el reemplazo falla, intentamos reabrir el JAR anterior para no
            // dejar al usuario sin Installer por una actualización fallida.
            ex.printStackTrace(System.err);
            try {
                Path targetJar = Path.of(args[1]).toAbsolutePath().normalize();
                if (Files.isRegularFile(targetJar)) {
                    new ProcessBuilder(javaExecutable().toString(), "-jar", targetJar.toString())
                            .directory(targetJar.getParent().toFile())
                            .start();
                }
            } catch (Exception ignored) { }
        }
        return true;
    }

    /**
     * Comprueba GitHub Releases y, si hay una versión más nueva, descarga,
     * verifica y entrega el relevo al helper. Devuelve true cuando el proceso
     * actual debe finalizar para permitir el reemplazo.
     */
    public static boolean checkAndUpdate(InstallerConfig config, String currentVersion) {
        return checkAndUpdate(config, currentVersion, () -> true);
    }

    @SuppressWarnings("unchecked")
    public static boolean checkAndUpdate(InstallerConfig config, String currentVersion, BooleanSupplier safeToRestart) {
        if (!config.updateEnabled()) {
            setState(UpdateState.IDLE);
            InstallerLogger.debug("UPDATE", "check.skip | update.enabled=false");
            return false;
        }

        Optional<Path> runningJarOpt = currentJarPath();
        if (runningJarOpt.isEmpty()) {
            setState(UpdateState.IDLE);
            InstallerLogger.debug("UPDATE", "check.skip | ejecución no proviene de un JAR");
            return false;
        }

        setState(UpdateState.CHECKING);
        Path runningJar = runningJarOpt.get();
        InstallerLogger.debug("UPDATE", "check.begin | current=" + currentVersion
                + " | jar=" + runningJar + " | api=" + config.updateReleaseApi());

        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.updateReleaseApi()))
                    .timeout(API_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "ASCENDERSMC-UNIVERSAL-INSTALLER/" + currentVersion)
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            InstallerLogger.debug("UPDATE", "api.response | status=" + response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                InstallerLogger.warn("UPDATE", "No se pudo consultar la última Release (HTTP " + response.statusCode() + "). Se continúa sin actualizar.");
                setState(UpdateState.IDLE);
                return false;
            }

            Object parsed = SimpleJson.parse(response.body());
            if (!(parsed instanceof Map<?, ?> raw)) throw new IOException("Respuesta de GitHub Releases inválida");
            Map<String,Object> release = (Map<String,Object>) raw;
            String tag = string(release.get("tag_name"));
            String latestVersion = normalizeVersion(tag);
            if (latestVersion.isBlank()) throw new IOException("La Release no contiene tag_name válido");

            if (compareVersions(latestVersion, currentVersion) <= 0) {
                InstallerLogger.debug("UPDATE", "up-to-date | latest=" + latestVersion);
                cleanupOldUpdates(config.updatesDir(), latestVersion);
                setState(UpdateState.IDLE);
                return false;
            }

            InstallerLogger.info("UPDATE", "Nueva versión disponible: " + currentVersion + " -> " + latestVersion);
            setState(UpdateState.DOWNLOADING);
            Object assetsObj = release.get("assets");
            if (!(assetsObj instanceof List<?> assets)) throw new IOException("La Release no contiene assets");

            Map<String,Object> jarAsset = findAsset(assets, config.updateAssetName());
            Map<String,Object> shaAsset = findAsset(assets, config.updateChecksumAssetName());
            if (jarAsset == null) throw new IOException("Falta asset de actualización: " + config.updateAssetName());
            if (shaAsset == null) throw new IOException("Falta asset de checksum: " + config.updateChecksumAssetName());

            String jarUrl = string(jarAsset.get("browser_download_url"));
            String shaUrl = string(shaAsset.get("browser_download_url"));
            validateGithubDownloadUrl(jarUrl);
            validateGithubDownloadUrl(shaUrl);

            String checksumText = fetchText(client, shaUrl, currentVersion);
            String expectedSha = checksumFor(checksumText, config.updateAssetName());
            if (expectedSha == null) throw new IOException("SHA256SUMS.txt no contiene " + config.updateAssetName());

            Path versionDir = config.updatesDir().resolve(latestVersion);
            Files.createDirectories(versionDir);
            Path candidate = versionDir.resolve(config.updateAssetName());
            Path part = candidate.resolveSibling(candidate.getFileName() + ".part");

            if (!Files.isRegularFile(candidate) || !Hashing.sha256(candidate).equalsIgnoreCase(expectedSha)) {
                Files.deleteIfExists(part);
                ResilientDownloader.download(jarUrl, part, DOWNLOAD_TIMEOUT, 4,
                        "ASCENDERSMC UNIVERSAL INSTALLER " + latestVersion);
                String actual = Hashing.sha256(part);
                InstallerLogger.debug("UPDATE", "sha256 | expected=" + expectedSha + " | actual=" + actual);
                if (!actual.equalsIgnoreCase(expectedSha)) {
                    Files.deleteIfExists(part);
                    throw new SecurityException("SHA-256 inválido para la actualización " + latestVersion);
                }
                FileOps.moveReplace(part, candidate);
            }

            // Verificación final incluso si vino de caché.
            String finalSha = Hashing.sha256(candidate);
            if (!finalSha.equalsIgnoreCase(expectedSha)) {
                Files.deleteIfExists(candidate);
                throw new SecurityException("El JAR de actualización en caché no coincide con SHA256SUMS.txt");
            }

            setState(UpdateState.READY_WAITING);
            while (safeToRestart != null && !safeToRestart.getAsBoolean()) {
                InstallerLogger.debug("UPDATE", "restart.defer | hay una instalación/reparación en curso");
                TimeUnit.MILLISECONDS.sleep(500);
            }

            setState(UpdateState.APPLYING);
            long pid = ProcessHandle.current().pid();
            ProcessBuilder helper = new ProcessBuilder(
                    javaExecutable().toString(), "-jar", candidate.toString(),
                    APPLY_UPDATE_ARG, runningJar.toString(), Long.toString(pid));
            helper.directory(runningJar.getParent().toFile());
            helper.start();
            InstallerLogger.info("UPDATE", "Actualización validada. Reiniciando en " + latestVersion + "...");
            return true;
        } catch (Exception ex) {
            setState(UpdateState.IDLE);
            InstallerLogger.warn("UPDATE", "No se pudo completar la comprobación automática: "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            InstallerLogger.debug("UPDATE", "check.fail | " + ex);
            return false;
        }
    }

    private static Map<String,Object> findAsset(List<?> assets, String wanted) {
        for (Object entry : assets) {
            if (!(entry instanceof Map<?,?> raw)) continue;
            @SuppressWarnings("unchecked") Map<String,Object> map = (Map<String,Object>) raw;
            if (wanted.equals(string(map.get("name")))) return map;
        }
        return null;
    }

    private static String fetchText(HttpClient client, String url, String version) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(API_TIMEOUT)
                .header("User-Agent", "ASCENDERSMC-UNIVERSAL-INSTALLER/" + version)
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " descargando checksum");
        }
        return response.body();
    }

    private static String checksumFor(String text, String filename) {
        if (text == null) return null;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length < 2) continue;
            String hash = parts[0].trim();
            String name = parts[1].trim();
            if (name.startsWith("*")) name = name.substring(1);
            if (name.equals(filename) && hash.matches("(?i)[0-9a-f]{64}")) return hash.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static void validateGithubDownloadUrl(String url) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("URL de asset vacía");
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("Asset sin HTTPS");
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("github.com") || host.endsWith(".githubusercontent.com"))) {
            throw new IOException("Host de actualización no permitido: " + host);
        }
    }

    private static Optional<Path> currentJarPath() {
        try {
            URI uri = UpdateService.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(uri).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return Optional.of(path);
            }
        } catch (Exception ignored) { }
        return Optional.empty();
    }

    private static Path javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", exe);
        if (Files.isRegularFile(candidate)) return candidate;
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static void waitForProcessExit(long pid, Duration timeout) throws InterruptedException, IOException {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) return;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (handle.get().isAlive() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(250);
        }
        if (handle.get().isAlive()) throw new IOException("El proceso anterior no terminó a tiempo (PID " + pid + ")");
    }

    private static String normalizeVersion(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    static int compareVersions(String left, String right) {
        int[] a = numericVersion(left);
        int[] b = numericVersion(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] numericVersion(String version) {
        String normalized = normalizeVersion(version);
        String core = normalized.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9]", "");
            out[i] = digits.isBlank() ? 0 : Integer.parseInt(digits);
        }
        return out;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void cleanupOldUpdates(Path updatesDir, String keepVersion) {
        if (!Files.isDirectory(updatesDir)) return;
        try (var stream = Files.list(updatesDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                if (dir.getFileName().toString().equals(keepVersion)) continue;
                try {
                    Files.walk(dir)
                            .sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                            });
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }
}
