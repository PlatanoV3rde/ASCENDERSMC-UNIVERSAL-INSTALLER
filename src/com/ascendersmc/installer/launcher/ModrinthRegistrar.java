package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.SimpleJson;

import java.awt.Desktop;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Registro Modrinth mediante el formato oficial .mrpack.
 *
 * IMPORTANTE: que exista una carpeta bajo ModrinthApp/profiles NO significa
 * que esté registrada en la base de datos app.db de Modrinth. Modificar app.db
 * desde un proceso externo también es inseguro (esquema migrable y app abierta).
 * Por tanto, la primera alta pasa siempre por el importador nativo de Modrinth.
 *
 * Al importar, Modrinth extrae nuestro marcador desde overrides/. El detector
 * usará ese marcador para encontrar el DIRECTORIO RESULTANTE, incluso cuando
 * Modrinth haya decidido otro nombre por una colisión con una carpeta huérfana.
 * El marcador nunca se crea directamente en la carpeta de origen.
 */
public final class ModrinthRegistrar {
    public static final String IMPORT_MARKER = "ASCENDERSMC-MODRINTH-IMPORT.txt";

    private ModrinthRegistrar() {}

    public static LauncherRegistration register(InstallerConfig config,
                                                 InstallerConfig.ProfileConfig profile,
                                                 InstallTarget target) throws Exception {
        Path nativeDir = findImportedInstance(target.launcherRoot(), profile.profile());
        if (nativeDir != null && nativeDir.toAbsolutePath().normalize()
                .equals(target.minecraftDir().toAbsolutePath().normalize())) {
            copyProfileIcon(nativeDir);
            InstallerLogger.info("LAUNCHER", "Instancia Modrinth importada previamente; archivos actualizados: " + nativeDir);
            return LauncherRegistration.ready("Instancia Modrinth importada y actualizada");
        }

        // Carpeta física sin marcador: puede ser una instalación huérfana de
        // 0.8.4. NO presumir que Modrinth la conoce por estar dentro de profiles.
        InstallerLogger.warn("MODRINTH", "Carpeta existente no equivale a registro nativo; preparando importación .mrpack | source="
                + target.minecraftDir() + " | nativeExpected=" + target.instanceRoot());
        Path state = config.stateDir(profile.profile()).resolve("modrinth");
        Files.createDirectories(state);
        Path mrpack = state.resolve("ASCENDERSMC-" + profile.profile().displayName() + ".mrpack");
        buildMrpack(profile, target.minecraftDir(), mrpack);
        InstallerLogger.info("MODRINTH", "Importación nativa pendiente | paquete=" + mrpack);

        // Abrir el archivo no equivale a que el usuario haya completado la
        // importación. Tampoco fallar la instalación si no existe asociación.
        boolean opened = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(mrpack.toFile());
                opened = true;
                InstallerLogger.info("MODRINTH", "Archivo .mrpack abierto; confirmar importación en Modrinth App");
            }
        } catch (Exception ex) {
            InstallerLogger.warn("MODRINTH", "No se pudo abrir automáticamente el .mrpack: " + ex.getMessage());
        }
        String instructions = (opened
                ? "Se abrió el archivo .mrpack. Completa la importación en Modrinth App. "
                : "En Modrinth App, entra en Crear instancia > Importar desde archivo y selecciona el .mrpack. ")
                + "Cuando aparezca ASCENDERSMC " + profile.profile().displayName()
                + " en la biblioteca, podrás jugar. Para futuras actualizaciones, vuelve a ejecutar el Installer: "
                + "identificará la carpeta realmente creada por Modrinth. No borres tus mundos ni las carpetas anteriores.";
        return new LauncherRegistration(true, mrpack, "Pendiente de importar en Modrinth", instructions);
    }

    /**
     * Localiza únicamente las importaciones confirmadas por el marcador que
     * Modrinth extrae al instalar el paquete. No confundir carpeta huérfana con
     * perfil nativo. Si hubiera duplicados, preferir el nombre canónico.
     */
    public static Path findImportedInstance(Path modrinthRoot, InstallerProfile profile) {
        if (modrinthRoot == null || profile == null) return null;
        Path profiles = modrinthRoot.resolve("profiles");
        if (!Files.isDirectory(profiles)) return null;
        List<Path> found = new ArrayList<>();
        try (var dirs = Files.list(profiles)) {
            for (Path path : dirs.filter(Files::isDirectory).toList()) {
                Path marker = path.resolve(IMPORT_MARKER);
                if (Files.isRegularFile(marker)) {
                    try {
                        if (markerContent(profile).equals(Files.readString(marker, StandardCharsets.UTF_8).trim())) {
                            found.add(path);
                        }
                    } catch (IOException ignored) { }
                }
            }
        } catch (IOException e) {
            InstallerLogger.warn("MODRINTH", "No se pudieron recorrer perfiles: " + e.getMessage());
            return null;
        }
        if (found.isEmpty()) return null;
        if (found.size() > 1) {
            InstallerLogger.warn("MODRINTH", "Varias instancias ASCENDERSMC importadas; priorizando la más reciente: " + found);
        }
        Path expected = profiles.resolve("ASCENDERSMC-" + profile.displayName());
        // Preferir la instancia canónica si fue importada realmente.
        for (Path path : found) if (path.equals(expected)) return path;
        found.sort(Comparator.comparingLong(ModrinthRegistrar::lastModified).reversed()
                .thenComparing(Path::toString));
        return found.get(0);
    }

    private static long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path.resolve(IMPORT_MARKER)).toMillis(); }
        catch (IOException e) { return 0; }
    }

    private static String markerContent(InstallerProfile profile) {
        return "ASCENDERSMC-MODRINTH:" + profile.name();
    }

    static void buildMrpack(InstallerConfig.ProfileConfig profile, Path source, Path output) throws Exception {
        buildMrpack(profile, source, output, IMPORT_MARKER, markerContent(profile.profile()));
    }

    /** Reutiliza el esquema Modrinth en importadores compatibles sin cruzar marcadores. */
    static void buildMrpack(InstallerConfig.ProfileConfig profile, Path source, Path output,
                            String markerName, String markerValue) throws Exception {
        if (!markerName.matches("[A-Za-z0-9_-]+\\.txt"))
            throw new IllegalArgumentException("Nombre de marcador no permitido: " + markerName);
        Map<String,Object> deps = new LinkedHashMap<>();
        deps.put("minecraft", profile.minecraftVersion());
        deps.put("neoforge", profile.neoForgeVersion());

        Map<String,Object> index = new LinkedHashMap<>();
        index.put("formatVersion", 1);
        index.put("game", "minecraft");
        index.put("versionId", "ascendersmc-" + profile.profile().id().toLowerCase(Locale.ROOT));
        index.put("name", "ASCENDERSMC " + profile.profile().displayName());
        index.put("summary", "ASCENDERSMC " + profile.profile().displayName());
        index.put("files", List.of());
        index.put("dependencies", deps);

        Path tmp = output.resolveSibling(output.getFileName() + ".part");
        Files.deleteIfExists(tmp);
        try {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
                // Un .mrpack jamás debe repetir una ruta, incluso si el sistema
                // de archivos la escribió con distinto uso de mayúsculas.
                Set<String> added = new HashSet<>();
                putBytes(zos, added, "modrinth.index.json", SimpleJson.stringify(index).getBytes(StandardCharsets.UTF_8));
                // Este marcador solo lo recibirá el perfil creado por la aplicación
                // de Modrinth, no la carpeta de staging ni la antigua huérfana.
                putBytes(zos, added, "overrides/" + markerName,
                        (markerValue + "\n").getBytes(StandardCharsets.UTF_8));
                byte[] icon = readBundledLogo();
                // Priorizamos el icono oficial sobre el que pueda existir en el
                // perfil viejo. Nunca podemos empaquetar ambos como icon.png.
                if (icon != null) putBytes(zos, added, "overrides/icon.png", icon);
                if (Files.isDirectory(source)) {
                    try (var stream = Files.walk(source)) {
                        for (Path file : stream.filter(Files::isRegularFile).toList()) {
                            String rel = source.relativize(file).toString().replace('\\', '/');
                            if (shouldSkip(rel) || rel.equalsIgnoreCase(markerName) || Files.isSymbolicLink(file)) continue;
                            String entryName = "overrides/" + rel;
                            if (!added.add(entryName.toLowerCase(Locale.ROOT))) {
                                InstallerLogger.debug("MODRINTH", "mrpack.duplicate.skip | " + entryName);
                                continue;
                            }
                            ZipEntry e = new ZipEntry(entryName);
                            zos.putNextEntry(e);
                            Files.copy(file, zos);
                            zos.closeEntry();
                        }
                    }
                }
            }
            try {
                Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        InstallerLogger.debug("MODRINTH", "mrpack.ready | file=" + output + " | bytes=" + Files.size(output));
    }

    private static boolean shouldSkip(String rel) {
        String n = rel.toLowerCase(Locale.ROOT);
        return n.equals(IMPORT_MARKER.toLowerCase(Locale.ROOT))
                || n.equals("icon.png")
                || n.equals("ascendersmc-profile.txt")
                || n.equals("leeme-ascendersmc.txt")
                || n.startsWith("logs/") || n.startsWith("crash-reports/")
                || n.startsWith("screenshots/") || n.startsWith(".cache/")
                || n.equals("app.db") || n.endsWith(".lock");
    }

    private static byte[] readBundledLogo() {
        try (InputStream in = ModrinthRegistrar.class.getResourceAsStream("/assets/logo.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception ex) {
            InstallerLogger.debug("MODRINTH", "icon.read.failed | " + ex.getMessage());
            return null;
        }
    }

    private static void copyProfileIcon(Path nativeRoot) {
        try {
            byte[] icon = readBundledLogo();
            if (icon == null) return;
            Files.write(nativeRoot.resolve("icon.png"), icon,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            InstallerLogger.debug("MODRINTH", "icon.write | " + nativeRoot.resolve("icon.png"));
        } catch (Exception ex) {
            InstallerLogger.warn("MODRINTH", "icon.write.failed | " + ex.getMessage());
        }
    }

    private static void putBytes(ZipOutputStream zos, Set<String> added, String name, byte[] bytes) throws IOException {
        if (!added.add(name.toLowerCase(Locale.ROOT))) {
            throw new ZipException("Entrada reservada duplicada: " + name);
        }
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }
}
