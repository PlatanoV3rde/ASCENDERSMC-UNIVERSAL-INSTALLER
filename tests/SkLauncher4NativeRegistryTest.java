package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.util.SimpleJson;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Reproduces the instances.json structure from real SKLauncher 4.0.54 without personal data. */
public class SkLauncher4NativeRegistryTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ascendersmc-sk4-");
        Path instances = root.resolve("instances");
        Path mc = instances.resolve("ascendersmc-cobbleworld");
        Path px = instances.resolve("ascendersmc-pixelmon");
        Files.createDirectories(mc.resolve("mods"));
        Files.createDirectories(px.resolve("mods"));
        Files.writeString(mc.resolve("mods/important.jar"), "preserve me");
        Files.createDirectories(instances.resolve("latest-release"));

        var vanilla = new LinkedHashMap<String,Object>();
        vanilla.put("id","latest-release");
        vanilla.put("name","Latest Release");
        vanilla.put("type","latest-release");
        vanilla.put("icon","Grass");
        vanilla.put("versionId","latest-release");
        vanilla.put("gameType","vanilla");
        vanilla.put("minecraftVersion","latest-release");
        vanilla.put("directory", instances.resolve("latest-release").toString());
        vanilla.put("createdAt","2026-10-14T06:01:36.884Z");
        vanilla.put("playTime",0);
        vanilla.put("sessionCount",0);
        var rootData = new LinkedHashMap<String,Object>();
        rootData.put("instances",new ArrayList<>(List.of(vanilla)));
        Files.writeString(root.resolve("instances.json"), SimpleJson.stringify(rootData));

        check(SkLauncher4Registry.findRegisteredInstance(instances, InstallerProfile.COBBLEWORLD) == null,
                "Una carpeta no implica registro");
        SkLauncher4Registry.register(instances, InstallerProfile.COBBLEWORLD,
                "1.21.1","21.1.248", mc);

        Object parsed = SimpleJson.parse(Files.readString(root.resolve("instances.json")));
        var list = (List<Map<String,Object>>) ((Map<String,Object>)parsed).get("instances");
        check(list.size() == 2, "Borró la instancia existente");
        check("latest-release".equals(list.get(0).get("id")), "Cambió la instancia de usuario");
        check(Files.isRegularFile(root.resolve("instances.json.ascendersmc.bak")), "No hay backup");
        Map<String,Object> cw = list.get(1);
        check("ascendersmc-cobbleworld".equals(cw.get("id")), "ID incorrecto");
        check("neoforge".equals(cw.get("gameType")), "Loader incorrecto");
        check("1.21.1".equals(cw.get("minecraftVersion")), "Minecraft incorrecto");
        check("21.1.248".equals(cw.get("loaderVersion")), "NeoForge incorrecto");
        check("1.21.1-neoforge-21.1.248".equals(cw.get("versionId")), "versionId incorrecto");
        check(Boolean.TRUE.equals(cw.get("installComplete")), "No marcado como instalado");
        String iconUri = (String) cw.get("icon");
        check(iconUri.startsWith("starship-icon://icons/ascendersmc-cobbleworld/")
                && iconUri.endsWith(".png"), "No registró el logo ASCENDERSMC en formato nativo");
        Path iconFile = root.resolve(iconUri.substring("starship-icon://".length()));
        check(Files.isRegularFile(iconFile) && Files.size(iconFile) > 1000,
                "No copió el logo a los iconos nativos de SKLauncher");
        try (var bundled = SkLauncher4NativeRegistryTest.class.getResourceAsStream("/assets/profile-cobbleworld.png")) {
            check(bundled != null && Arrays.equals(Files.readAllBytes(iconFile), bundled.readAllBytes()),
                    "El logo nativo es distinto del recurso incluido");
        }
        check(mc.toString().equals(cw.get("directory")), "Carpeta incorrecta");
        check(Files.exists(mc.resolve("mods/important.jar")), "Eliminó un mod original");
        check(mc.equals(SkLauncher4Registry.findRegisteredInstance(instances,InstallerProfile.COBBLEWORLD)),
                "No reconoce registro creado");

        SkLauncher4Registry.register(instances, InstallerProfile.COBBLEWORLD,
                "1.21.1","21.1.248", mc);
        parsed=SimpleJson.parse(Files.readString(root.resolve("instances.json")));
        list=(List<Map<String,Object>>)((Map<String,Object>)parsed).get("instances");
        check(list.size()==2,"Actualización duplicó instancia");
        check(iconUri.equals(list.get(1).get("icon")), "Actualización perdió el logo de ASCENDERSMC");
        // A manually selected custom icon must not be replaced on repair.
        list.get(1).put("icon", "starship-icon://icons/user-choice/custom.png");
        Files.writeString(root.resolve("instances.json"), SimpleJson.stringify(Map.of("instances", list)));
        SkLauncher4Registry.register(instances, InstallerProfile.COBBLEWORLD,
                "1.21.1", "21.1.248", mc);
        parsed=SimpleJson.parse(Files.readString(root.resolve("instances.json")));
        list=(List<Map<String,Object>>)((Map<String,Object>)parsed).get("instances");
        check("starship-icon://icons/user-choice/custom.png".equals(list.get(1).get("icon")),
                "No debe sobrescribir el logo personalizado por el jugador");
        check(SkLauncher4Registry.executableMatches(root.resolve("sklauncher.exe").toString(),
                    root.resolve("sklauncher.exe")), "No detecta el ejecutable de forma exacta");
        check(!SkLauncher4Registry.executableMatches(root.resolve("other/sklauncher.exe").toString(),
                    root.resolve("sklauncher.exe")), "Detectó un launcher diferente");
        check(!SkLauncher4Registry.executableMatches(root.resolve("javaw.exe").toString(),
                    root.resolve("sklauncher.exe")), "Confundió Minecraft con SKLauncher");

        SkLauncher4Registry.register(instances, InstallerProfile.PIXELMON,
                "1.21.1","21.1.248",px);
        parsed=SimpleJson.parse(Files.readString(root.resolve("instances.json")));
        list=(List<Map<String,Object>>)((Map<String,Object>)parsed).get("instances");
        check(list.size()==3,"Pixelmon no tiene instancia independiente");
        check(instances.resolve("ascendersmc-pixelmon").equals(
                SkLauncher4Registry.findRegisteredInstance(instances,InstallerProfile.PIXELMON)),
                "Pixelmon no se encuentra");
        check(String.valueOf(list.get(2).get("icon"))
                .startsWith("starship-icon://icons/ascendersmc-pixelmon/"),
                "Pixelmon no tiene su propio icono ASCENDERSMC");

        Path malicious = root.resolve("outside");
        Files.createDirectories(malicious);
        boolean blocked=false;
        try { SkLauncher4Registry.register(instances, InstallerProfile.COBBLEWORLD,
                "1.21.1","21.1.248",malicious); } catch (SecurityException expected) { blocked=true; }
        check(blocked,"No rechazó carpeta fuera de raíz");
        check(list.size()==3, "Debe conservar instancias existentes");
        // Invalid registry must be rejected rather than overwritten.
        String good = Files.readString(root.resolve("instances.json"));
        Files.writeString(root.resolve("instances.json"), "{BROKEN");
        boolean refused = false;
        try { SkLauncher4Registry.register(instances, InstallerProfile.COBBLEWORLD,
                "1.21.1","21.1.248",mc); } catch (Exception expected) { refused = true; }
        check(refused && Files.readString(root.resolve("instances.json")).equals("{BROKEN"),
                "No debe sobrescribir un registro dañado o de formato desconocido");
        Files.writeString(root.resolve("instances.json"), good);
        System.out.println("PASS: registro nativo, iconos COBBLEWORLD/PIXELMON, iconos personalizados, procesos, respaldo, aislamiento y actualizaciones");
    }
}
