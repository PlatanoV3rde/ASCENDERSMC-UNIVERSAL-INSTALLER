package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.util.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class PrismRegistrar {
    private PrismRegistrar() {}
    public static void register(InstallerConfig.ProfileConfig profile,InstallTarget target)throws Exception{
        Path instance=target.instanceRoot(); if(instance==null)throw new IllegalArgumentException("instanceRoot ausente"); Files.createDirectories(instance); Files.createDirectories(target.minecraftDir());
        String cfg="[General]\nConfigVersion=1.2\nInstanceType=OneSix\nname=ASCENDERSMC "+profile.profile().displayName()+"\nOverrideMemory=true\nMinMemAlloc=1024\nMaxMemAlloc=4096\n";
        Files.writeString(instance.resolve("instance.cfg"),cfg,StandardCharsets.UTF_8);
        Map<String,Object> minecraft=new LinkedHashMap<>(); minecraft.put("uid","net.minecraft"); minecraft.put("version",profile.minecraftVersion()); minecraft.put("important",true);
        Map<String,Object> neo=new LinkedHashMap<>(); neo.put("uid","net.neoforged"); neo.put("version",profile.neoForgeVersion());
        Map<String,Object> root=new LinkedHashMap<>(); root.put("formatVersion",1); root.put("components",List.of(minecraft,neo));
        Files.writeString(instance.resolve("mmc-pack.json"),SimpleJson.stringify(root),StandardCharsets.UTF_8);
        InstallerLogger.info("LAUNCHER", "Instancia Prism registrada en "+instance);
    }
}
