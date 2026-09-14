package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.util.SimpleJson;

import java.awt.Desktop;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Modrinth App mantiene la metadata actual de perfiles en app.db. Para no
 * acoplar el Installer a un esquema SQLite interno que cambia por migraciones,
 * el primer registro se realiza mediante el formato oficial .mrpack. Cuando el
 * perfil ya existe en Modrinth, futuras ejecuciones actualizan directamente su
 * carpeta real.
 */
public final class ModrinthRegistrar {
    private ModrinthRegistrar() {}

    public static void register(InstallerConfig config, InstallerConfig.ProfileConfig profile, InstallTarget target) throws Exception {
        if (target.instanceRoot() != null
                && target.minecraftDir().toAbsolutePath().normalize().equals(target.instanceRoot().toAbsolutePath().normalize())) {
            copyProfileIcon(target.instanceRoot());
            InstallerLogger.info("LAUNCHER", "Perfil Modrinth ya registrado; archivos actualizados directamente: " + target.minecraftDir());
            return;
        }

        Path state = config.stateDir(profile.profile()).resolve("modrinth");
        Files.createDirectories(state);
        Path mrpack = state.resolve("ASCENDERSMC-" + profile.profile().displayName() + ".mrpack");
        buildMrpack(profile, target.minecraftDir(), mrpack);
        InstallerLogger.info("LAUNCHER", "Paquete de importación Modrinth creado: " + mrpack);

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Windows no permite abrir el .mrpack mediante su asociación de archivos: " + mrpack);
        }

        Desktop.getDesktop().open(mrpack.toFile());
        InstallerLogger.info("LAUNCHER", "Importación .mrpack entregada a Modrinth App mediante asociación de Windows");
        // La importación es asíncrona. Si Modrinth crea la carpeta esperada,
        // dejamos también el icon.png de ASCENDERSMC dentro de la instancia.
        if (target.instanceRoot() != null) waitAndCopyProfileIcon(target.instanceRoot());
    }

    private static void buildMrpack(InstallerConfig.ProfileConfig profile, Path source, Path output) throws Exception {
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
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            putBytes(zos, "modrinth.index.json", SimpleJson.stringify(index).getBytes(StandardCharsets.UTF_8));
            byte[] icon = readBundledLogo();
            if (icon != null) {
                putBytes(zos, "icon.png", icon);
                putBytes(zos, "overrides/icon.png", icon);
            }
            if (Files.isDirectory(source)) {
                try (var stream = Files.walk(source)) {
                    for (Path file : stream.filter(Files::isRegularFile).toList()) {
                        String rel = source.relativize(file).toString().replace('\\', '/');
                        if (rel.equalsIgnoreCase("ASCENDERSMC-PROFILE.txt") || rel.equalsIgnoreCase("LEEME-ASCENDERSMC.txt")) continue;
                        ZipEntry e = new ZipEntry("overrides/" + rel);
                        zos.putNextEntry(e);
                        Files.copy(file, zos);
                        zos.closeEntry();
                    }
                }
            }
        }
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        InstallerLogger.debug("MODRINTH", "mrpack.ready | file=" + output + " | bytes=" + Files.size(output));
    }

    private static byte[] readBundledLogo() {
        try (InputStream in = ModrinthRegistrar.class.getResourceAsStream("/assets/logo.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception ex) {
            InstallerLogger.debug("MODRINTH", "icon.read.failed | " + ex.getMessage());
            return null;
        }
    }

    private static void waitAndCopyProfileIcon(Path instanceRoot) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 90; i++) {
                try {
                    if (Files.isDirectory(instanceRoot)) {
                        copyProfileIcon(instanceRoot);
                        return;
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) { }
            }
        }, "ascendersmc-modrinth-icon");
        t.setDaemon(true);
        t.start();
    }

    private static void copyProfileIcon(Path instanceRoot) {
        try {
            byte[] icon = readBundledLogo();
            if (icon == null) return;
            Files.createDirectories(instanceRoot);
            Files.write(instanceRoot.resolve("icon.png"), icon, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            InstallerLogger.debug("MODRINTH", "icon.write | " + instanceRoot.resolve("icon.png"));
        } catch (Exception ex) {
            InstallerLogger.debug("MODRINTH", "icon.write.failed | " + ex.getMessage());
        }
    }

    private static void putBytes(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }
}
