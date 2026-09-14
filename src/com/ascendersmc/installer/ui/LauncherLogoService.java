package com.ascendersmc.installer.ui;

import com.ascendersmc.installer.model.InstallTarget;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Logos embebidos en el JAR. No usa iconos del sistema, favicons web,
 * caché externa ni monogramas alternativos.
 */
public final class LauncherLogoService {
    private LauncherLogoService() {}

    public static Icon load(InstallTarget target, int size) {
        if (target == null) return null;
        String path = "/assets/launchers/" + target.launcherType().id().toLowerCase(Locale.ROOT) + ".png";
        try (InputStream in = LauncherLogoService.class.getResourceAsStream(path)) {
            if (in == null) return null;
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            return new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
        } catch (IOException ignored) {
            return null;
        }
    }
}
