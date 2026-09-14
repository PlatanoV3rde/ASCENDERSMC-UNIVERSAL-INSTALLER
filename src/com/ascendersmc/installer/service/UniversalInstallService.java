package com.ascendersmc.installer.service;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.launcher.*;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.util.InstallerLogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.BiConsumer;

public final class UniversalInstallService {
    private final InstallerConfig config;

    public UniversalInstallService(InstallerConfig config) {
        this.config = config;
    }

    public LauncherRegistration install(InstallerProfile selected,
                                        InstallTarget target,
                                        BiConsumer<Integer, String> progress) throws Exception {
        InstallerLogger.debug("FLOW", "install.start | profile=" + selected.displayName()
                + " | launcher=" + target.launcherType().label()
                + " | installed=" + target.installed()
                + " | gameDir=" + target.minecraftDir()
                + " | launcherRoot=" + target.launcherRoot()
                + " | instanceRoot=" + target.instanceRoot()
                + " | executable=" + target.launcherExecutable());

        if (!target.installed()) {
            InstallerLogger.debug("FLOW", "install.abort | launcher no instalado");
            throw new IllegalStateException(target.launcherType().label() + " no está instalado en esta computadora.");
        }

        var profile = config.profile(selected);
        InstallerLogger.debug("FLOW", "profile.config | minecraft=" + profile.minecraftVersion()
                + " | neoforge=" + profile.neoForgeVersion()
                + " | modpackPrefix=" + profile.modpackAssetPrefix()
                + " | resourcePack=" + profile.resourcePackAssetName());

        validateSafety(target);
        InstallerLogger.debug("FLOW", "safety.ok | destino aislado confirmado");

        Files.createDirectories(target.minecraftDir());
        Files.createDirectories(config.stateDir(selected));
        Files.createDirectories(config.importsDir());
        InstallerLogger.debug("FLOW", "directories.ready | minecraftDir=" + target.minecraftDir()
                + " | stateDir=" + config.stateDir(selected)
                + " | importsDir=" + config.importsDir());

        progress.accept(5, "Preparando perfil ASCENDERSMC...");
        writeProfileInfo(profile, target);

        InstallerLogger.debug("FLOW", "phase.modpack.begin");
        new ModpackService(config).install(profile, target, progress);
        InstallerLogger.debug("FLOW", "phase.modpack.end");

        InstallerLogger.debug("FLOW", "phase.resourcepack.begin");
        String rp = new ResourcePackService(config).install(profile, target, progress);
        InstallerLogger.debug("FLOW", "phase.resourcepack.end | asset=" + rp);

        InstallerLogger.debug("FLOW", "phase.first-run.begin");
        FirstRunConfigurator.apply(config, target, rp);
        InstallerLogger.debug("FLOW", "phase.first-run.end");

        InstallerLogger.debug("FLOW", "phase.neoforge.begin");
        new NeoForgeService(config).ensure(profile, target, progress);
        InstallerLogger.debug("FLOW", "phase.neoforge.end");

        LauncherRegistration registration = LauncherRegistration.ready("Perfil preparado");
        progress.accept(94, "Registrando perfil...");
        if (target.registerAutomatically()) {
            InstallerLogger.debug("FLOW", "phase.register.begin | launcher=" + target.launcherType().label());
            registration = switch (target.launcherType()) {
                case OFFICIAL -> {
                    OfficialLauncherRegistrar.register(profile, target);
                    yield LauncherRegistration.ready("Registrado automáticamente");
                }
                case SKLAUNCHER -> {
                    OfficialLauncherRegistrar.register(profile, target);
                    yield LauncherRegistration.ready("Perfil SKLauncher registrado");
                }
                case PRISM -> {
                    PrismRegistrar.register(profile, target);
                    yield LauncherRegistration.ready("Registrado automáticamente");
                }
                case TLAUNCHER -> {
                    TLauncherRegistrar.register(profile, target);
                    yield LauncherRegistration.ready("Versión TLauncher registrada");
                }
                case CURSEFORGE -> CurseForgeRegistrar.register(config, profile, target);
                case MODRINTH -> {
                    ModrinthRegistrar.register(config, profile, target);
                    yield LauncherRegistration.ready("Importación entregada a Modrinth");
                }
                default -> LauncherRegistration.ready("Perfil aislado preparado");
            };
            InstallerLogger.debug("FLOW", "phase.register.end | importRequired=" + registration.importRequired()
                    + " | importPackage=" + registration.importPackage());
        } else {
            InstallerLogger.debug("FLOW", "phase.register.skip | launcher sin registrador automático");
        }

        writeInstructions(profile, target, registration);
        progress.accept(100, registration.importRequired() ? "Paquete listo para importar" : "Instalación completada");
        InstallerLogger.info("INSTALL", "Perfil " + selected.displayName() + " preparado en " + target.minecraftDir()
                + (registration.importPackage() == null ? "" : " | import=" + registration.importPackage()));
        InstallerLogger.debug("FLOW", "install.end | success=true | importRequired=" + registration.importRequired());
        return registration;
    }

    private void validateSafety(InstallTarget target) throws Exception {
        Path mc = target.minecraftDir().toAbsolutePath().normalize();
        InstallerLogger.debug("SAFETY", "Validando destino=" + mc);
        String app = System.getenv("APPDATA");
        if (app != null) {
            Path vanilla = Path.of(app, ".minecraft").toAbsolutePath().normalize();
            InstallerLogger.debug("SAFETY", "minecraft genérico=" + vanilla);
            if (mc.equals(vanilla) || mc.startsWith(vanilla.resolve("mods"))) {
                InstallerLogger.warn("SAFETY", "Destino rechazado porque apunta a .minecraft genérico: " + mc);
                throw new SecurityException("Por seguridad ASCENDERSMC Universal Installer nunca instala sobre .minecraft genérico.");
            }
        }
    }

    private void writeProfileInfo(InstallerConfig.ProfileConfig p, InstallTarget t) throws Exception {
        Path file = t.minecraftDir().resolve("ASCENDERSMC-PROFILE.txt");
        String text = "ASCENDERSMC UNIVERSAL INSTALLER\nprofile=" + p.profile().name()
                + "\nminecraft=" + p.minecraftVersion()
                + "\nneoforge=" + p.neoForgeVersion()
                + "\ngameDir=" + t.minecraftDir()
                + "\nlauncher=" + t.launcherType() + "\n";
        Files.writeString(file, text, StandardCharsets.UTF_8);
        InstallerLogger.debug("FLOW", "profile-info.write | file=" + file);
    }

    private void writeInstructions(InstallerConfig.ProfileConfig p,
                                   InstallTarget t,
                                   LauncherRegistration registration) throws Exception {
        String extra;
        if (registration.importRequired()) {
            extra = registration.instructions()
                    + "\nPaquete de importación:\n" + registration.importPackage();
        } else {
            extra = switch (t.launcherType()) {
                case OFFICIAL -> "El perfil fue registrado automáticamente en Minecraft Launcher como ASCENDERSMC " + p.profile().displayName() + ".";
                case SKLAUNCHER -> "El perfil fue registrado automáticamente en SKLauncher usando launcher_profiles.json y Game Directory aislado.";
                case PRISM -> "La instancia fue registrada automáticamente en Prism Launcher.";
                case TLAUNCHER -> "La versión ASCENDERSMC fue registrada en TLauncher con --gameDir aislado, por lo que no usa .minecraft\\mods global.";
                case CURSEFORGE -> "La instancia custom fue creada directamente en CurseForge con minecraftinstance.json nativo y metadata real de NeoForge. No se requiere importación manual.";
                case MODRINTH -> "El perfil fue entregado a Modrinth App mediante el formato .mrpack soportado por Modrinth.";
                default -> "Se preparó un perfil aislado para " + t.launcherType().label() + ".";
            };
        }
        Path file = t.minecraftDir().resolve("LEEME-ASCENDERSMC.txt");
        String body = "ASCENDERSMC " + p.profile().displayName()
                + "\n\nLauncher: " + t.launcherType().label()
                + "\nCarpeta del perfil:\n" + t.minecraftDir()
                + "\n\nMinecraft: " + p.minecraftVersion()
                + "\nNeoForge: " + p.neoForgeVersion()
                + "\nServidor: " + config.serverHost()
                + "\n\n" + extra
                + "\n\nIMPORTANTE: esta instalación usa un perfil separado y nunca modifica %APPDATA%\\.minecraft\\mods del jugador.\n";
        Files.writeString(file, body, StandardCharsets.UTF_8);
        InstallerLogger.debug("FLOW", "instructions.write | file=" + file);
    }
}
