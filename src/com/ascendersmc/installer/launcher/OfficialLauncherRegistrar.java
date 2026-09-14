package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.*;
import com.ascendersmc.installer.util.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class OfficialLauncherRegistrar {
    private OfficialLauncherRegistrar() {}

    @SuppressWarnings("unchecked")
    public static void register(InstallerConfig.ProfileConfig profile, InstallTarget target) throws Exception {
        Path root=target.launcherRoot(); if(root==null)throw new IllegalArgumentException("launcherRoot ausente");
        Files.createDirectories(root);
        Path file=root.resolve("launcher_profiles.json");
        Map<String,Object> doc;
        if(Files.isRegularFile(file)){
            Object parsed=SimpleJson.parse(Files.readString(file,StandardCharsets.UTF_8));
            if(!(parsed instanceof Map<?,?> raw))throw new IllegalStateException("launcher_profiles.json inválido");
            doc=new LinkedHashMap<>((Map<String,Object>)raw);
            Files.copy(file,file.resolveSibling("launcher_profiles.json.ascendersmc.backup"),StandardCopyOption.REPLACE_EXISTING);
        }else doc=new LinkedHashMap<>();
        Object pObj=doc.get("profiles"); Map<String,Object> profiles;
        if(pObj instanceof Map<?,?> raw)profiles=new LinkedHashMap<>((Map<String,Object>)raw); else profiles=new LinkedHashMap<>();
        String id="ASCENDERSMC-"+profile.profile().displayName();
        Map<String,Object> p=new LinkedHashMap<>();
        p.put("created",Instant.now().toString()); p.put("gameDir",target.minecraftDir().toString()); p.put("lastVersionId","neoforge-"+profile.neoForgeVersion()); p.put("name","ASCENDERSMC "+profile.profile().displayName()); p.put("type","custom");
        profiles.put(id,p); doc.put("profiles",profiles); doc.put("selectedProfile", id);
        Path tmp=file.resolveSibling(file.getFileName()+".tmp"); Files.writeString(tmp,SimpleJson.stringify(doc),StandardCharsets.UTF_8); FileOps.moveReplace(tmp,file);
        InstallerLogger.info("LAUNCHER", "Perfil oficial registrado: "+id+" | gameDir="+target.minecraftDir());
    }
}
