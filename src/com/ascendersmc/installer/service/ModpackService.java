package com.ascendersmc.installer.service;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;

public final class ModpackService {
    private final InstallerConfig config;
    private final GitHubReleaseResolver github = new GitHubReleaseResolver();

    public ModpackService(InstallerConfig config) {
        this.config = config;
    }

    public void install(InstallerConfig.ProfileConfig profile, InstallTarget target, BiConsumer<Integer, String> progress) throws Exception {
        Path state = config.stateDir(profile.profile());
        Path mods = target.minecraftDir().resolve("mods");
        Files.createDirectories(state);
        Files.createDirectories(target.minecraftDir());
        InstallerLogger.debug("MODPACK", "install.start | state=" + state + " | mods=" + mods);

        recover(state, mods);

        progress.accept(20, "Consultando modpack...");
        InstallerLogger.debug("MODPACK", "release.resolve | api=" + profile.modpackReleaseApi()
                + " | exact=" + profile.modpackAssetName() + " | prefix=" + profile.modpackAssetPrefix());
        var asset = github.resolveZip(profile.modpackReleaseApi(), profile.modpackAssetName(), profile.modpackAssetPrefix());
        InstallerLogger.debug("MODPACK", "release.asset | version=" + asset.version() + " | name=" + asset.name()
                + " | size=" + asset.size() + " | sha256=" + asset.sha256());

        Path cache = state.resolve("cache");
        Files.createDirectories(cache);
        Path zip = cache.resolve(asset.version().replaceAll("[^a-zA-Z0-9._-]", "_") + "-" + asset.name());
        boolean cacheValid = Files.isRegularFile(zip)
                && Files.size(zip) == asset.size()
                && Hashing.sha256(zip).equalsIgnoreCase(asset.sha256());
        InstallerLogger.debug("MODPACK", "cache.check | file=" + zip + " | valid=" + cacheValid
                + " | existingSize=" + (Files.isRegularFile(zip) ? Files.size(zip) : -1));

        if (!cacheValid) {
            Path part = zip.resolveSibling(zip.getFileName() + ".part");
            progress.accept(25, "Descargando " + asset.name() + "...");
            InstallerLogger.debug("MODPACK", "download.begin | url=" + asset.url() + " | part=" + part);
            ResilientDownloader.download(asset.url(), part, Duration.ofMinutes(30), 6, "modpack " + profile.profile().displayName());
            long actualSize = Files.size(part);
            InstallerLogger.debug("MODPACK", "download.end | bytes=" + actualSize);
            if (asset.size() > 0 && actualSize != asset.size()) {
                throw new java.io.IOException("Tamaño del modpack incorrecto");
            }
            String actualHash = Hashing.sha256(part);
            InstallerLogger.debug("MODPACK", "hash.check | expected=" + asset.sha256() + " | actual=" + actualHash);
            if (!actualHash.equalsIgnoreCase(asset.sha256())) {
                throw new SecurityException("SHA-256 incorrecto del modpack");
            }
            FileOps.moveReplace(part, zip);
            InstallerLogger.debug("MODPACK", "cache.commit | file=" + zip);
        }

        progress.accept(48, "Extrayendo modpack...");
        Path staging = state.resolve("staging");
        FileOps.deleteTree(staging);
        Files.createDirectories(staging);
        InstallerLogger.debug("MODPACK", "extract.begin | zip=" + zip + " | staging=" + staging);
        FileOps.extractZip(zip, staging);
        Path stagedMods = staging.resolve("mods");
        long jarCount = Files.isDirectory(stagedMods) ? FileOps.countJars(stagedMods) : 0;
        InstallerLogger.debug("MODPACK", "extract.end | stagedMods=" + stagedMods + " | jars=" + jarCount);
        if (!Files.isDirectory(stagedMods) || jarCount == 0) {
            throw new java.io.IOException("El ZIP no contiene mods/ con .jar");
        }

        Path marker = state.resolve(".installing-modpack");
        Files.writeString(marker, "version=" + asset.version() + "\n", StandardCharsets.UTF_8);
        Path backup = state.resolve("mods.backup");
        FileOps.deleteTree(backup);
        boolean moved = false;
        try {
            if (Files.exists(mods)) {
                InstallerLogger.debug("MODPACK", "backup.begin | from=" + mods + " | to=" + backup);
                Files.move(mods, backup, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            }
            Files.createDirectories(mods.getParent());
            Files.move(stagedMods, mods, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(state.resolve("pack-version.txt"), asset.version(), StandardCharsets.UTF_8);
            writeIntegrity(state, mods);
            Files.deleteIfExists(marker);
            FileOps.deleteTree(backup);
            InstallerLogger.debug("MODPACK", "commit.ok | mods=" + mods + " | jars=" + FileOps.countJars(mods));
        } catch (Exception e) {
            InstallerLogger.error("MODPACK", "commit.fail; iniciando rollback", e);
            FileOps.deleteTree(mods);
            if (moved && Files.exists(backup)) {
                Files.move(backup, mods, StandardCopyOption.REPLACE_EXISTING);
                InstallerLogger.debug("MODPACK", "rollback.ok | restored=" + mods);
            }
            throw e;
        } finally {
            FileOps.deleteTree(staging);
        }

        progress.accept(65, "Modpack instalado: " + asset.version());
        InstallerLogger.info("MODPACK", "Instalado " + asset.version() + " en " + mods);
        InstallerLogger.debug("MODPACK", "install.end | success=true");
    }

    private void recover(Path state, Path mods) throws Exception {
        Path marker = state.resolve(".installing-modpack");
        Path backup = state.resolve("mods.backup");
        Path staging = state.resolve("staging");
        InstallerLogger.debug("MODPACK", "recover.check | marker=" + Files.exists(marker)
                + " | backup=" + Files.exists(backup) + " | staging=" + Files.exists(staging));
        if (Files.exists(marker)) {
            InstallerLogger.warn("REPAIR", "Instalación de modpack interrumpida; restaurando backup.");
            FileOps.deleteTree(mods);
            if (Files.exists(backup)) Files.move(backup, mods, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(marker);
        } else if (Files.exists(backup)) {
            FileOps.deleteTree(backup);
        }
        FileOps.deleteTree(staging);
    }

    private void writeIntegrity(Path state, Path mods) throws Exception {
        Properties p = new Properties();
        try (var s = Files.list(mods)) {
            for (Path f : s.filter(Files::isRegularFile)
                    .filter(x -> x.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted().toList()) {
                p.setProperty(f.getFileName().toString(), Files.size(f) + "|" + Hashing.sha256(f));
            }
        }
        Path manifest = state.resolve("mods-integrity.properties");
        try (var out = Files.newOutputStream(manifest)) {
            p.store(out, "ASCENDERSMC Universal Installer");
        }
        InstallerLogger.debug("MODPACK", "integrity.write | file=" + manifest + " | entries=" + p.size());
    }
}
