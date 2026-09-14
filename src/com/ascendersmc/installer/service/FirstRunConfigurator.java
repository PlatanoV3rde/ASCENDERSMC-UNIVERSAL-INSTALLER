package com.ascendersmc.installer.service;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.FileOps;
import com.ascendersmc.installer.util.InstallerLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class FirstRunConfigurator {
    private FirstRunConfigurator() {}

    public static void apply(InstallerConfig config, InstallTarget target, String resourcePackName) throws Exception {
        Path state = target.minecraftDir().resolve(".ascendersmc-first-run");
        boolean firstRun = !Files.exists(state);
        InstallerLogger.debug("FIRST-RUN", "apply | firstRun=" + firstRun + " | dir=" + target.minecraftDir() + " | rp=" + resourcePackName);
        if (!firstRun) return;

        writeOptions(config, target.minecraftDir(), resourcePackName);
        writeServers(config, target.minecraftDir());
        Files.writeString(state, "initialized=true\n", StandardCharsets.UTF_8);
        InstallerLogger.debug("FIRST-RUN", "marker.write | " + state);
    }

    private static void writeOptions(InstallerConfig config, Path mc, String asset) throws Exception {
        Path options = mc.resolve("options.txt");
        List<String> lines = Files.isRegularFile(options)
                ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8))
                : new ArrayList<>();
        set(lines, "guiScale:", "guiScale:" + config.guiScale());
        String q = "\"file/" + asset.replace("\\", "/") + "\"";
        set(lines, "resourcePacks:", "resourcePacks:[\"vanilla\",\"mod_resources\"," + q + "]");
        set(lines, "incompatibleResourcePacks:", "incompatibleResourcePacks:[" + q + "]");
        Path tmp = options.resolveSibling("options.txt.tmp");
        Files.createDirectories(mc);
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        FileOps.moveReplace(tmp, options);
        InstallerLogger.debug("FIRST-RUN", "options.write | file=" + options + " | guiScale=" + config.guiScale());
    }

    private static void set(List<String> lines, String prefix, String value) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                lines.set(i, value);
                return;
            }
        }
        lines.add(value);
    }

    private static void writeServers(InstallerConfig config, Path mc) throws Exception {
        Path target = mc.resolve("servers.dat");
        Path tmp = mc.resolve("servers.dat.tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeByte(10);
            utf(out, "");
            out.writeByte(9);
            utf(out, "servers");
            out.writeByte(10);
            out.writeInt(1);
            str(out, "name", "ASCENDERSMC");
            str(out, "ip", config.serverHost());
            String mode = config.serverResourcePackMode();
            if ("SI".equals(mode) || "YES".equals(mode)) byt(out, "acceptTextures", 1);
            else if ("NO".equals(mode)) byt(out, "acceptTextures", 0);
            out.writeByte(0);
            out.writeByte(0);
        }
        FileOps.moveReplace(tmp, target);
        InstallerLogger.debug("FIRST-RUN", "servers.write | file=" + target + " | server=" + config.serverHost() + " | rpMode=" + config.serverResourcePackMode());
    }

    private static void byt(DataOutputStream o, String n, int v) throws Exception { o.writeByte(1); utf(o, n); o.writeByte(v); }
    private static void str(DataOutputStream o, String n, String v) throws Exception { o.writeByte(8); utf(o, n); utf(o, v); }
    private static void utf(DataOutputStream o, String s) throws Exception {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeShort(b.length);
        o.write(b);
    }
}
