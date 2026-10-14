package com.ascendersmc.installer.launcher;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.model.*;
import com.ascendersmc.installer.service.NeoForgeService;
import java.io.InputStream;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SkLauncher4AutoImportTest {
    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    public static void main(String[] args) throws Exception {
        Path temporary=Files.createTempDirectory("sk4auto-test-");
        try(InputStream in=SkLauncher4AutoImporter.class.getResourceAsStream("/assets/automation/sklauncher4-import.ps1")) {
            check(in != null, "Script UI Automation no empaquetado");
            String script=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            check(script.contains("UIAutomationClient") && script.contains("OpenFileDialog"), "Script incompleto");
            check(script.contains("FindImported") && script.contains("MarkerValue"), "No verifica el resultado nativo");
            check(script.contains("dialogProcess.ProcessName"), "Puede interactuar con dialogos ajenos");
        }
        InstallTarget target = new InstallTarget(LauncherType.SKLAUNCHER, "SKLauncher", true,
                temporary.resolve("staging"), temporary.resolve(".sklauncher"),
                temporary.resolve(".sklauncher/instances/ascendersmc"), null, true, "");
        check(SkLauncherRegistrar.isVersion4(target), "No detecto 4.x");
        var config=InstallerConfig.load();
        AtomicBoolean loaderSkipped=new AtomicBoolean();
        new NeoForgeService(config).ensure(config.profile(InstallerProfile.COBBLEWORLD), target,
                (progress,message) -> { if (message.contains("gestionado por SKLauncher")) loaderSkipped.set(true); });
        check(loaderSkipped.get(), "SKLauncher 4.x intenta instalar NeoForge en .sklauncher incorrectamente");
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            check(!SkLauncher4AutoImporter.tryImport(config, InstallerProfile.COBBLEWORLD, target, temporary.resolve("none.mrpack")),
                "No debe marcar autoimportacion exitosa en Linux");
        }
        System.out.println("PASS: motor SKLauncher 4.x, NeoForge nativo, script empaquetado y verificacion de importacion");
    }
}
