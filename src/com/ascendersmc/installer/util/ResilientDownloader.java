package com.ascendersmc.installer.util;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;

/** Descarga HTTP/1.1 reanudable basada en el sistema probado del ASCENDERSMC-LAUNCHER. */
public final class ResilientDownloader {
    private static final int BUFFER_SIZE = 256 * 1024;
    private ResilientDownloader() {}

    public static void download(String url, Path destination, Duration timeout, int attempts, String description) throws Exception {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Exception last = null;
        InstallerLogger.debug("NETWORK", "download.start | description=" + description + " | url=" + url
                + " | destination=" + destination + " | attempts=" + attempts + " | timeout=" + timeout);

        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            long existing = safeSize(destination);
            InstallerLogger.debug("NETWORK", "attempt.begin | " + description + " | attempt=" + attempt + "/" + attempts + " | existing=" + existing);
            try {
                HttpURLConnection c = open(url, existing, timeout);
                int code = c.getResponseCode();
                boolean append = code == 206 && existing > 0;
                if (code == 200 && existing > 0) {
                    append = false;
                    existing = 0;
                }
                InstallerLogger.debug("NETWORK", "response | code=" + code + " | append=" + append
                        + " | contentLength=" + c.getContentLengthLong() + " | contentRange=" + c.getHeaderField("Content-Range"));
                if (code < 200 || code >= 300) {
                    c.disconnect();
                    throw new IOException("HTTP " + code + " descargando " + description);
                }

                long length = c.getContentLengthLong();
                long received = 0;
                StandardOpenOption[] opts = append
                        ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                        : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};

                try (InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER_SIZE);
                     OutputStream out = new BufferedOutputStream(Files.newOutputStream(destination, opts), BUFFER_SIZE)) {
                    byte[] b = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = in.read(b)) >= 0) {
                        if (n > 0) {
                            out.write(b, 0, n);
                            received += n;
                        }
                    }
                } finally {
                    c.disconnect();
                }

                InstallerLogger.debug("NETWORK", "attempt.end | received=" + received + " | finalSize=" + safeSize(destination));
                if (length >= 0 && received != length) {
                    throw new IOException("Transferencia incompleta: " + received + "/" + length);
                }
                InstallerLogger.debug("NETWORK", "download.success | description=" + description + " | bytes=" + safeSize(destination));
                return;
            } catch (Exception ex) {
                last = ex;
                InstallerLogger.debug("NETWORK", "attempt.fail | attempt=" + attempt + " | error=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                if (attempt >= attempts) break;
                InstallerLogger.warn("NETWORK", description + " falló (" + attempt + "/" + attempts + "). Se conservan " + safeSize(destination) + " bytes.");
                Thread.sleep(Math.min(8000L, 700L << Math.min(4, attempt - 1)));
            }
        }
        throw new IOException("No se pudo completar " + description + ". El .part se conserva para reanudar.", last);
    }

    private static HttpURLConnection open(String original, long existing, Duration timeout) throws IOException {
        URI current = URI.create(original);
        int redirects = 0;
        while (true) {
            InstallerLogger.debug("NETWORK", "connect | uri=" + current + " | rangeStart=" + existing);
            HttpURLConnection c = (HttpURLConnection) current.toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            c.setConnectTimeout(30_000);
            c.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(30_000, timeout.toMillis())));
            c.setRequestProperty("User-Agent", "ASCENDERSMC-UNIVERSAL-INSTALLER/0.7.4");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isBlank()) throw new IOException("Redirección sin Location");
                if (++redirects > 10) throw new IOException("Demasiadas redirecciones");
                current = current.resolve(loc);
                InstallerLogger.debug("NETWORK", "redirect | count=" + redirects + " | next=" + current);
                continue;
            }
            if (code == 416 && existing > 0) {
                InstallerLogger.debug("NETWORK", "range.416 | reiniciando desde cero");
                c.disconnect();
                existing = 0;
                continue;
            }
            return c;
        }
    }

    private static long safeSize(Path p) {
        try { return Files.isRegularFile(p) ? Files.size(p) : 0; }
        catch (IOException e) { return 0; }
    }
}
