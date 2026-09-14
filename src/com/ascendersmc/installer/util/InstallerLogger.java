package com.ascendersmc.installer.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class InstallerLogger {
    private static final CopyOnWriteArrayList<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Path logFile;
    private static volatile boolean debugEnabled = true;
    private InstallerLogger() {}

    public static void init(Path logsDir, boolean debug) throws IOException {
        debugEnabled = debug;
        Files.createDirectories(logsDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        logFile = logsDir.resolve("installer-" + stamp + ".log");
        info("BOOT", "ASCENDERSMC UNIVERSAL INSTALLER iniciado");
        debug("BOOT", "Debug=" + debugEnabled + " | log=" + logFile.toAbsolutePath().normalize());
    }

    public static void addListener(Consumer<String> listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<String> listener) {
        LISTENERS.remove(listener);
    }

    public static void info(String category, String message) { write("INFO", category, message, null); }
    public static void debug(String category, String message) { if (debugEnabled) write("DEBUG", category, message, null); }
    public static void warn(String category, String message) { write("WARN", category, message, null); }
    public static void error(String category, String message, Throwable error) { write("ERROR", category, message, error); }

    public static boolean debugEnabled() { return debugEnabled; }
    public static Path currentLogFile() { return logFile; }

    public static synchronized Path exportTo(Path destination) throws IOException {
        if (destination == null) throw new IOException("Destino de log inválido");
        Path source = logFile;
        if (source == null || !Files.isRegularFile(source)) throw new IOException("Todavía no existe un archivo de registro para exportar.");
        Path target = destination.toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        debug("LOG", "Registro exportado a " + target);
        return target;
    }

    private static synchronized void write(String level, String category, String message, Throwable error) {
        String cat = category == null || category.isBlank() ? "GENERAL" : category;
        String msg = message == null ? "" : message;
        String line = "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + level + "] [" + cat + "] " + msg;
        System.out.println(line);
        publish(line);

        if (logFile != null) {
            try {
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (error != null) {
                    StringWriter sw = new StringWriter();
                    try (PrintWriter pw = new PrintWriter(sw)) { error.printStackTrace(pw); }
                    Files.writeString(logFile, sw.toString() + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static void publish(String line) {
        for (Consumer<String> listener : LISTENERS) {
            try { listener.accept(line); } catch (Exception ignored) {}
        }
    }
}
