package com.ascendersmc.installer.service;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.zip.ZipFile;

public final class ResourcePackService {
    private final InstallerConfig config;
    private final GitHubReleaseResolver github = new GitHubReleaseResolver();

    public ResourcePackService(InstallerConfig config) {
        this.config = config;
    }

    public String install(InstallerConfig.ProfileConfig profile, InstallTarget target, BiConsumer<Integer, String> progress) throws Exception {
        progress.accept(67, "Comprobando resource pack...");
        InstallerLogger.debug("RESOURCEPACK", "release.resolve | api=" + profile.resourcePackReleaseApi() + " | asset=" + profile.resourcePackAssetName());
        var asset = github.resolveExact(profile.resourcePackReleaseApi(), profile.resourcePackAssetName());
        InstallerLogger.debug("RESOURCEPACK", "release.asset | id=" + asset.id() + " | size=" + asset.size() + " | sha256=" + asset.sha256());

        Path state = config.stateDir(profile.profile()).resolve("resourcepack");
        Files.createDirectories(state);
        Path part = state.resolve(asset.name() + "." + asset.id() + ".part");
        Path rp = target.minecraftDir().resolve("resourcepacks").resolve(asset.name());
        Files.createDirectories(rp.getParent());

        boolean valid = Files.isRegularFile(rp)
                && Files.size(rp) == asset.size()
                && Hashing.sha256(rp).equalsIgnoreCase(asset.sha256())
                && hasPackMeta(rp);
        InstallerLogger.debug("RESOURCEPACK", "local.check | file=" + rp + " | valid=" + valid
                + " | size=" + (Files.isRegularFile(rp) ? Files.size(rp) : -1));

        if (!valid) {
            progress.accept(70, "Descargando resource pack...");
            InstallerLogger.debug("RESOURCEPACK", "download.begin | url=" + asset.url() + " | part=" + part);
            ResilientDownloader.download(asset.url(), part, Duration.ofMinutes(15), 6, "resource pack");
            long size = Files.size(part);
            InstallerLogger.debug("RESOURCEPACK", "download.end | bytes=" + size);
            if (asset.size() > 0 && size != asset.size()) throw new java.io.IOException("Tamaño de resource pack incorrecto");
            String hash = Hashing.sha256(part);
            InstallerLogger.debug("RESOURCEPACK", "hash.check | expected=" + asset.sha256() + " | actual=" + hash);
            if (!hash.equalsIgnoreCase(asset.sha256())) throw new SecurityException("SHA-256 incorrecto del resource pack");
            if (!hasPackMeta(part)) throw new java.io.IOException("Resource pack sin pack.mcmeta en raíz");

            Path backup = state.resolve(asset.name() + ".backup");
            Files.deleteIfExists(backup);
            if (Files.isRegularFile(rp)) {
                FileOps.moveReplace(rp, backup);
                InstallerLogger.debug("RESOURCEPACK", "backup.created | " + backup);
            }
            try {
                FileOps.moveReplace(part, rp);
                Files.deleteIfExists(backup);
                InstallerLogger.debug("RESOURCEPACK", "commit.ok | " + rp);
            } catch (Exception e) {
                InstallerLogger.error("RESOURCEPACK", "commit.fail; intentando rollback", e);
                Files.deleteIfExists(rp);
                if (Files.isRegularFile(backup)) FileOps.moveReplace(backup, rp);
                throw e;
            }
        }

        progress.accept(80, "Resource pack listo");
        InstallerLogger.debug("RESOURCEPACK", "install.end | asset=" + asset.name());
        return asset.name();
    }

    private boolean hasPackMeta(Path zip) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            boolean ok = z.getEntry("pack.mcmeta") != null;
            InstallerLogger.debug("RESOURCEPACK", "pack.mcmeta | file=" + zip + " | present=" + ok);
            return ok;
        } catch (Exception e) {
            InstallerLogger.debug("RESOURCEPACK", "pack.mcmeta | file=" + zip + " | error=" + e.getMessage());
            return false;
        }
    }
}
