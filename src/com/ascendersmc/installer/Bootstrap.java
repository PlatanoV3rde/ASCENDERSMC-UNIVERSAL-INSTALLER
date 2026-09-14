package com.ascendersmc.installer;

import javax.swing.JOptionPane;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bootstrap deliberadamente compilado para Java 8.
 *
 * Permite que un doble clic asociado a Java 8/17 pueda abrir el mismo JAR,
 * localizar un Java 21 ya instalado por Minecraft/launchers y relanzarse con
 * él. El resto de la aplicación continúa compilado para Java 21.
 */
public final class Bootstrap {
    private Bootstrap() {}

    public static void main(String[] args) {
        try {
            if (javaMajor(System.getProperty("java.version")) >= 21) {
                launchApplicationInCurrentJvm(args);
                return;
            }

            File java21 = findJava21();
            if (java21 != null) {
                relaunch(java21, args);
                return;
            }

            showError("ASCENDERSMC UNIVERSAL INSTALLER requiere Java 21.\n\n"
                    + "No se encontró automáticamente un runtime Java 21 de Minecraft o de otro launcher.\n"
                    + "Instala/ejecuta Minecraft 1.21.1 una vez o instala Java 21 y vuelve a abrir este JAR.");
        } catch (Throwable ex) {
            ex.printStackTrace();
            showError("No se pudo iniciar ASCENDERSMC UNIVERSAL INSTALLER.\n\n"
                    + ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
        }
    }

    private static void launchApplicationInCurrentJvm(String[] args) throws Exception {
        Class<?> app = Class.forName("com.ascendersmc.installer.App");
        Method main = app.getMethod("main", String[].class);
        main.invoke(null, new Object[]{args});
    }

    private static File findJava21() {
        Set<String> candidates = new LinkedHashSet<String>();

        addJavaHome(candidates, System.getenv("JAVA_HOME"));

        String appData = System.getenv("APPDATA");
        String localAppData = System.getenv("LOCALAPPDATA");
        String userProfile = System.getenv("USERPROFILE");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");

        if (appData != null) {
            addRuntimeRoot(candidates, new File(appData, ".minecraft\\runtime"));
            addRuntimeRoot(candidates, new File(appData, "PrismLauncher\\java"));
            addRuntimeRoot(candidates, new File(appData, "PrismLauncher\\runtime"));
            addRuntimeRoot(candidates, new File(appData, "sklauncher\\runtime"));
        }
        if (localAppData != null) {
            addRuntimeRoot(candidates, new File(localAppData, ".minecraft\\runtime"));
            addRuntimeRoot(candidates, new File(localAppData, "Packages\\Microsoft.4297127D64EC6_8wekyb3d8bbwe\\LocalCache\\Local\\runtime"));
        }
        if (userProfile != null) {
            addRuntimeRoot(candidates, new File(userProfile, "curseforge\\minecraft\\Install\\runtime"));
            addRuntimeRoot(candidates, new File(userProfile, ".lunarclient\\jre"));
        }
        if (programFiles != null) {
            addRuntimeRoot(candidates, new File(programFiles, "Minecraft Launcher\\runtime"));
            addRuntimeRoot(candidates, new File(programFiles, "Java"));
            addRuntimeRoot(candidates, new File(programFiles, "Eclipse Adoptium"));
        }
        if (programFilesX86 != null) {
            addRuntimeRoot(candidates, new File(programFilesX86, "Minecraft Launcher\\runtime"));
        }

        // PATH puede contener una instalación Java 21 aun cuando JAVA_HOME no esté configurado.
        String path = System.getenv("PATH");
        if (path != null) {
            String[] entries = path.split(File.pathSeparator);
            for (int i = 0; i < entries.length; i++) {
                File dir = new File(entries[i]);
                File javaw = new File(dir, isWindows() ? "javaw.exe" : "java");
                if (javaw.isFile()) candidates.add(javaw.getAbsolutePath());
                File java = new File(dir, isWindows() ? "java.exe" : "java");
                if (java.isFile()) candidates.add(java.getAbsolutePath());
            }
        }

        for (String value : candidates) {
            File executable = new File(value);
            if (executable.isFile() && probeJavaMajor(executable) >= 21) return executable;
        }
        return null;
    }

    private static void addJavaHome(Set<String> out, String home) {
        if (home == null || home.trim().length() == 0) return;
        File bin = new File(home, "bin");
        File preferred = new File(bin, isWindows() ? "javaw.exe" : "java");
        File console = new File(bin, isWindows() ? "java.exe" : "java");
        if (preferred.isFile()) out.add(preferred.getAbsolutePath());
        if (console.isFile()) out.add(console.getAbsolutePath());
    }

    private static void addRuntimeRoot(Set<String> out, File root) {
        if (root == null || !root.isDirectory()) return;
        scanJavaExecutables(root, out, 0, 8);
    }

    private static void scanJavaExecutables(File dir, Set<String> out, int depth, int maxDepth) {
        if (dir == null || depth > maxDepth || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile()) {
                String name = f.getName().toLowerCase();
                if ((isWindows() && ("javaw.exe".equals(name) || "java.exe".equals(name)))
                        || (!isWindows() && "java".equals(name))) {
                    out.add(f.getAbsolutePath());
                }
            } else if (f.isDirectory()) {
                scanJavaExecutables(f, out, depth + 1, maxDepth);
            }
        }
    }

    private static int probeJavaMajor(File javaExe) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe.getAbsolutePath(), "-version");
            pb.redirectErrorStream(true);
            process = pb.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();
            if (line == null) return -1;
            int firstQuote = line.indexOf('"');
            int secondQuote = firstQuote < 0 ? -1 : line.indexOf('"', firstQuote + 1);
            String version = firstQuote >= 0 && secondQuote > firstQuote ? line.substring(firstQuote + 1, secondQuote) : line;
            return javaMajor(version);
        } catch (Throwable ignored) {
            return -1;
        } finally {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static int javaMajor(String version) {
        if (version == null) return -1;
        String v = version.trim();
        try {
            if (v.startsWith("1.")) {
                int end = v.indexOf('.', 2);
                String major = end > 2 ? v.substring(2, end) : v.substring(2);
                return Integer.parseInt(major.replaceAll("[^0-9].*$", ""));
            }
            int end = 0;
            while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
            return end == 0 ? -1 : Integer.parseInt(v.substring(0, end));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void relaunch(File java21, String[] args) throws Exception {
        File jar = currentJar();
        List<String> command = new ArrayList<String>();
        command.add(java21.getAbsolutePath());
        command.add("-jar");
        command.add(jar.getAbsolutePath());
        if (args != null) {
            for (int i = 0; i < args.length; i++) command.add(args[i]);
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        File parent = jar.getParentFile();
        if (parent != null) pb.directory(parent);
        pb.start();
    }

    private static File currentJar() throws Exception {
        URI uri = Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        File file = new File(uri);
        if (!file.isFile() || !file.getName().toLowerCase().endsWith(".jar")) {
            throw new IllegalStateException("El bootstrap no se está ejecutando desde un JAR");
        }
        return file.getAbsoluteFile();
    }

    private static void showError(String message) {
        try {
            JOptionPane.showMessageDialog(null, message, "ASCENDERSMC UNIVERSAL INSTALLER", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            System.err.println(message);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
