package com.ascendersmc.installer.service;

import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.SimpleJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

final class GitHubReleaseResolver {
    record Asset(String version, long id, String name, String url, long size, String updatedAt, String sha256) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    Asset resolveZip(String api, String exact, String prefix) throws Exception {
        return resolve(api, exact, prefix, true);
    }

    Asset resolveExact(String api, String exact) throws Exception {
        return resolve(api, exact, "", false);
    }

    @SuppressWarnings("unchecked")
    private Asset resolve(String api, String exact, String prefix, boolean zip) throws Exception {
        InstallerLogger.debug("GITHUB", "request.begin | api=" + api + " | exact=" + exact + " | prefix=" + prefix + " | zip=" + zip);
        long start = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "ASCENDERSMC-UNIVERSAL-INSTALLER/0.8.0")
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        InstallerLogger.debug("GITHUB", "request.end | status=" + res.statusCode() + " | ms=" + ((System.nanoTime() - start) / 1_000_000));
        if (res.statusCode() < 200 || res.statusCode() >= 300) throw new IOException("HTTP " + res.statusCode() + " consultando GitHub Releases");

        Object parsed = SimpleJson.parse(res.body());
        if (!(parsed instanceof Map<?, ?> raw)) throw new IOException("Respuesta inválida de GitHub");
        Map<String, Object> root = (Map<String, Object>) raw;
        String version = str(root.get("tag_name"));
        if (version.isBlank()) version = str(root.get("name"));
        if (version.isBlank()) version = "latest";

        Object ao = root.get("assets");
        if (!(ao instanceof List<?> assets)) throw new IOException("Release sin assets");
        InstallerLogger.debug("GITHUB", "release.parsed | version=" + version + " | assets=" + assets.size());

        Map<?, ?> selected = null;
        String lowerPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (Object o : assets) {
            if (!(o instanceof Map<?, ?> a)) continue;
            String name = str(a.get("name"));
            InstallerLogger.debug("GITHUB", "asset.scan | name=" + name + " | size=" + num(a.get("size")));
            if (zip && !name.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
            if (exact != null && !exact.isBlank() && name.equalsIgnoreCase(exact)) {
                selected = a;
                break;
            }
            if ((exact == null || exact.isBlank()) && !lowerPrefix.isBlank() && name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                selected = a;
                break;
            }
        }
        if (selected == null) throw new IOException("No se encontró el asset esperado en GitHub Release");

        String digest = str(selected.get("digest"));
        if (digest.toLowerCase(Locale.ROOT).startsWith("sha256:")) digest = digest.substring(7);
        if (!digest.matches("(?i)[0-9a-f]{64}")) throw new IOException("El asset GitHub no publica digest SHA-256 válido");

        Asset asset = new Asset(version, num(selected.get("id")), str(selected.get("name")),
                str(selected.get("browser_download_url")), num(selected.get("size")),
                str(selected.get("updated_at")), digest);
        InstallerLogger.debug("GITHUB", "asset.selected | id=" + asset.id() + " | name=" + asset.name()
                + " | size=" + asset.size() + " | updated=" + asset.updatedAt());
        return asset;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long num(Object o) { return o instanceof Number n ? n.longValue() : -1; }
}
