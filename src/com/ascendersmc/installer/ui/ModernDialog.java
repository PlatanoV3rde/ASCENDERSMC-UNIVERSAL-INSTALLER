package com.ascendersmc.installer.ui;

import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.launcher.LauncherRegistration;
import com.ascendersmc.installer.util.InstallerLogger;
import com.ascendersmc.installer.service.UpdateService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;

public final class ModernDialog extends JDialog {
    public enum Type {INFO, SUCCESS, ERROR}

    private ModernDialog(Window owner, String title, JComponent body, Type type, Dimension size) {
        super(owner);
        setModal(true);
        setUndecorated(true);
        setSize(size);
        setMinimumSize(size);
        setLocationRelativeTo(owner);
        setBackground(new Color(0, 0, 0, 0));
        setContentPane(buildContent(title, body, type));
    }

    private JComponent buildContent(String title, JComponent body, Type type) {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(new Color(88, 71, 132), 1));
        root.setBackground(new Color(20, 18, 31));

        JPanel card = new JPanel(new BorderLayout(0, 20));
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(24, 28, 24, 28));

        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 20f));
        titleBar.add(titleLabel, BorderLayout.WEST);
        card.add(titleBar, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(24, 0));
        center.setOpaque(false);
        center.add(new Badge(type, 84), BorderLayout.WEST);
        center.add(body, BorderLayout.CENTER);
        card.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bottom.setOpaque(false);
        ModernButton ok = new ModernButton("ACEPTAR");
        ok.setPreferredSize(new Dimension(160, 46));
        ok.addActionListener(e -> dispose());
        bottom.add(ok);
        card.add(bottom, BorderLayout.SOUTH);

        root.add(card, BorderLayout.CENTER);
        return root;
    }

    public static void showInfo(Window owner, String title, String message) {
        show(owner, title, message, Type.INFO);
    }

    public static void showError(Window owner, String title, String message) {
        show(owner, title, message, Type.ERROR);
    }

    public static void show(Window owner, String title, String message, Type type) {
        JTextArea area = textArea(message, 15f);
        area.setRows(6);
        area.setColumns(44);
        area.setPreferredSize(new Dimension(560, 150));

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(6, 2, 6, 4));
        body.add(area, BorderLayout.CENTER);
        new ModernDialog(owner, title, body, type, new Dimension(790, 380)).setVisible(true);
    }

    public static boolean confirmInstallerClose(Window owner,
                                                boolean installationInProgress,
                                                UpdateService.UpdateState updateState) {
        final boolean updateBusy = updateState != null && updateState.isBusy();
        final boolean critical = installationInProgress || (updateState != null && updateState.isCritical());
        final boolean showStatus = installationInProgress || updateBusy;
        final boolean[] confirmed = {false};

        JDialog dialog = new JDialog(owner);
        dialog.setModal(true);
        dialog.setUndecorated(true);
        Dimension dialogSize = showStatus ? new Dimension(900, 420) : new Dimension(860, 300);
        dialog.setSize(dialogSize);
        dialog.setMinimumSize(dialogSize);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setBackground(new Color(0, 0, 0, 0));

        Color borderColor = critical ? new Color(165, 75, 90) : new Color(88, 71, 132);
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(borderColor, 1));
        root.setBackground(new Color(20, 18, 31));

        JPanel content = new JPanel(new BorderLayout(24, 0));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(28, 34, 18, 34));

        JPanel badgeWrap = new JPanel(new BorderLayout());
        badgeWrap.setOpaque(false);
        badgeWrap.setPreferredSize(new Dimension(76, 76));
        badgeWrap.setMinimumSize(new Dimension(76, 76));
        badgeWrap.setMaximumSize(new Dimension(76, 76));
        JPanel badgeTop = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        badgeTop.setOpaque(false);
        badgeTop.add(new Badge(critical ? Type.ERROR : Type.INFO, 64));
        badgeWrap.add(badgeTop, BorderLayout.NORTH);
        content.add(badgeWrap, BorderLayout.WEST);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("¿CERRAR ASCENDERSMC UNIVERSAL INSTALLER?");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(10));

        JTextArea message = textArea(critical
                ? "Hay un proceso activo. Cerrar ahora puede interrumpir la instalación, la reparación del perfil o la actualización del propio Installer.\n\nConfirma el cierre únicamente si deseas detener el proceso actual."
                : "¿Deseas cerrar realmente el Installer? Esta confirmación ayuda a evitar cierres accidentales.", 14f);
        message.setForeground(new Color(205, 198, 224));
        message.setRows(critical ? 4 : 2);
        message.setAlignmentX(Component.LEFT_ALIGNMENT);
        message.setMaximumSize(new Dimension(Integer.MAX_VALUE, critical ? 104 : 56));
        body.add(message);

        if (showStatus) {
            body.add(Box.createVerticalStrut(14));
            JPanel statusCard = new JPanel();
            statusCard.setLayout(new BoxLayout(statusCard, BoxLayout.Y_AXIS));
            statusCard.setBackground(new Color(29, 24, 42));
            statusCard.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(62, 53, 86)),
                    new EmptyBorder(11, 14, 11, 14)));
            statusCard.setAlignmentX(Component.LEFT_ALIGNMENT);
            statusCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, installationInProgress && updateBusy ? 72 : 48));
            if (installationInProgress) statusCard.add(statusLine("INSTALACIÓN / REPARACIÓN", "EN CURSO"));
            if (installationInProgress && updateBusy) statusCard.add(Box.createVerticalStrut(4));
            if (updateBusy) {
                String stateText = updateState == null ? "EN CURSO" : updateState.displayName().toUpperCase(java.util.Locale.ROOT);
                statusCard.add(statusLine("ACTUALIZACIÓN DEL INSTALLER", stateText));
            }
            body.add(statusCard);
        }

        content.add(body, BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(49, 43, 67)),
                new EmptyBorder(16, 34, 22, 34)));

        ModernButton keepOpen = new ModernButton(critical ? "CONTINUAR PROCESO" : "NO, CONTINUAR",
                new Color(45, 42, 66), new Color(74, 65, 105));
        keepOpen.setPreferredSize(new Dimension(210, 46));
        keepOpen.addActionListener(e -> dialog.dispose());

        ModernButton close = new ModernButton(critical ? "CERRAR DE TODOS MODOS" : "SÍ, CERRAR",
                new Color(125, 52, 70), new Color(163, 74, 95));
        close.setPreferredSize(new Dimension(220, 46));
        close.addActionListener(e -> { confirmed[0] = true; dialog.dispose(); });

        buttons.add(keepOpen);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(keepOpen);
        dialog.setVisible(true);
        return confirmed[0];
    }

    private static JPanel statusLine(String label, String value) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        JLabel left = new JLabel(label);
        left.setForeground(new Color(175, 168, 197));
        left.setFont(left.getFont().deriveFont(Font.BOLD, 11.5f));
        JLabel right = new JLabel(value);
        right.setForeground(new Color(255, 187, 116));
        right.setFont(right.getFont().deriveFont(Font.BOLD, 11.5f));
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        return row;
    }

    public static void showLogExported(Window owner, Path saved) {
        JDialog dialog = new JDialog(owner);
        dialog.setModal(true);
        dialog.setUndecorated(true);
        dialog.setSize(960, 470);
        dialog.setMinimumSize(new Dimension(960, 470));
        dialog.setLocationRelativeTo(owner);
        dialog.setBackground(new Color(0, 0, 0, 0));

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(new Color(88, 71, 132), 1));
        root.setBackground(new Color(20, 18, 31));

        JPanel content = new JPanel(new BorderLayout(24, 0));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(34, 38, 20, 38));
        content.add(new Badge(Type.SUCCESS, 96), BorderLayout.WEST);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Registro exportado");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        body.add(title);
        body.add(Box.createVerticalStrut(8));
        JLabel subtitle = new JLabel("El archivo está listo para adjuntarlo y analizarlo.");
        subtitle.setForeground(new Color(187, 180, 205));
        subtitle.setFont(subtitle.getFont().deriveFont(14f));
        body.add(subtitle);
        body.add(Box.createVerticalStrut(24));

        JPanel pathCard = new JPanel(new BorderLayout());
        pathCard.setBackground(new Color(12, 12, 23));
        pathCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 60, 103)),
                new EmptyBorder(18, 20, 18, 20)));
        JTextArea path = textArea(saved == null ? "—" : saved.toAbsolutePath().normalize().toString(), 13.5f);
        path.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        path.setRows(3);
        pathCard.setPreferredSize(new Dimension(680, 96));
        pathCard.add(path, BorderLayout.CENTER);
        body.add(pathCard);
        content.add(body, BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.setBorder(new EmptyBorder(16, 38, 30, 38));
        ModernButton close = new ModernButton("CERRAR");
        close.setPreferredSize(new Dimension(160, 46));
        close.addActionListener(e -> dialog.dispose());
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.setVisible(true);
    }

    public static void showInstallationCompleted(Window owner,
                                                 InstallerProfile profile,
                                                 InstallTarget target,
                                                 LauncherRegistration registration) {
        boolean importRequired = registration != null && registration.importRequired();
        Path importPackage = registration == null ? null : registration.importPackage();

        JDialog dialog = new JDialog(owner);
        dialog.setModal(true);
        dialog.setUndecorated(true);
        dialog.setSize(820, importRequired ? 500 : 420);
        dialog.setMinimumSize(new Dimension(820, importRequired ? 500 : 420));
        dialog.setLocationRelativeTo(owner);
        dialog.setBackground(new Color(0, 0, 0, 0));

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createLineBorder(new Color(88, 71, 132), 1));
        root.setBackground(new Color(20, 18, 31));

        JPanel header = new JPanel(new BorderLayout(22, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(26, 30, 18, 30));
        header.add(new Badge(Type.SUCCESS, 88), BorderLayout.WEST);

        JPanel headerText = new JPanel();
        headerText.setOpaque(false);
        headerText.setLayout(new BoxLayout(headerText, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(importRequired ? "Paquete de importación listo" : "Instalación completada");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel(importRequired
                ? "Falta únicamente importarlo desde el launcher para crear el perfil."
                : "El perfil ASCENDERSMC quedó preparado correctamente.");
        subtitle.setForeground(new Color(188, 181, 205));
        subtitle.setFont(subtitle.getFont().deriveFont(14f));
        headerText.add(title);
        headerText.add(Box.createVerticalStrut(7));
        headerText.add(subtitle);
        header.add(headerText, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setBorder(new EmptyBorder(4, 30, 14, 30));
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        JSeparator separator = new JSeparator();
        separator.setForeground(new Color(72, 62, 101));
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        center.add(separator);
        center.add(Box.createVerticalStrut(18));
        center.add(infoRow("MODPACK", profile.displayName()));
        center.add(Box.createVerticalStrut(12));
        center.add(infoRow("LAUNCHER", target.launcherType().label()));
        center.add(Box.createVerticalStrut(12));
        center.add(infoRow(importRequired ? "ARCHIVO" : "CARPETA",
                importRequired && importPackage != null ? importPackage.toString() : target.minecraftDir().toString()));
        center.add(Box.createVerticalStrut(12));
        center.add(infoRow("PERFIL", registration == null
                ? (target.registerAutomatically() ? "Registrado automáticamente" : "Perfil aislado preparado")
                : registration.status()));
        if (importRequired) {
            center.add(Box.createVerticalStrut(16));
            JTextArea hint = textArea(registration.instructions(), 13.2f);
            hint.setForeground(new Color(205, 198, 224));
            hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
            center.add(hint);
        }
        root.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.setBorder(new EmptyBorder(12, 30, 26, 30));
        if (importRequired && importPackage != null) {
            ModernButton openFolder = new ModernButton("ABRIR CARPETA", new Color(45, 42, 66), new Color(74, 65, 105));
            openFolder.setPreferredSize(new Dimension(190, 46));
            openFolder.addActionListener(e -> openImportFolder(importPackage));
            buttons.add(openFolder);
        }
        ModernButton close = new ModernButton("CERRAR");
        close.setPreferredSize(new Dimension(160, 46));
        close.addActionListener(e -> dialog.dispose());
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.setVisible(true);
    }

    private static void openImportFolder(Path file) {
        try {
            if (file == null) return;
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
            } else if (Desktop.isDesktopSupported()) {
                Path parent = file.getParent();
                if (parent != null) Desktop.getDesktop().open(parent.toFile());
            }
        } catch (Exception ex) {
            InstallerLogger.warn("UI", "No se pudo abrir la carpeta de importación: " + ex.getMessage());
        }
    }

    private static JTextArea textArea(String message, float fontSize) {
        JTextArea area = new JTextArea(message == null ? "" : message);
        area.setOpaque(false);
        area.setForeground(new Color(223, 218, 236));
        area.setFont(area.getFont().deriveFont(fontSize));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(null);
        area.setFocusable(false);
        return area;
    }

    private static JComponent infoRow(String label, String value) {
        JLabel v = new JLabel(asHtml(value));
        v.setForeground(Color.WHITE);
        v.setFont(v.getFont().deriveFont(Font.BOLD, 13f));
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        JLabel l = new JLabel(label);
        l.setForeground(new Color(170, 164, 187));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12.5f));
        l.setPreferredSize(new Dimension(100, 24));
        row.add(l, BorderLayout.WEST);
        row.add(v, BorderLayout.CENTER);
        return row;
    }

    private static String asHtml(String s) {
        if (s == null) return "—";
        String escaped = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (escaped.length() <= 82) return "<html>" + escaped + "</html>";
        int split = escaped.lastIndexOf('\\', Math.min(escaped.length() - 1, 68));
        if (split < 20) split = Math.min(68, escaped.length());
        return "<html>" + escaped.substring(0, split + 1) + "<br>" + escaped.substring(split + 1) + "</html>";
    }

    private static final class Badge extends JComponent {
        private final Type type;
        private Badge(Type type, int size) {
            this.type = type;
            Dimension fixed = new Dimension(size, size);
            setPreferredSize(fixed);
            setMinimumSize(fixed);
            setMaximumSize(fixed);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int diameter = Math.max(1, Math.min(getWidth(), getHeight()) - 1);
            int ox = (getWidth() - diameter) / 2;
            int oy = (getHeight() - diameter) / 2;
            Color fill = switch (type) {
                case INFO -> new Color(76, 105, 205);
                case SUCCESS -> new Color(54, 165, 111);
                case ERROR -> new Color(166, 70, 93);
            };
            g2.setColor(fill);
            g2.fillOval(ox, oy, diameter, diameter);
            g2.setColor(new Color(142, 255, 196, type == Type.SUCCESS ? 220 : 130));
            g2.drawOval(ox + 1, oy + 1, Math.max(1, diameter - 3), Math.max(1, diameter - 3));
            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(24f, diameter * 0.42f)));
            String s = switch (type) {
                case INFO -> "i";
                case SUCCESS -> "✓";
                case ERROR -> "!";
            };
            FontMetrics fm = g2.getFontMetrics();
            int x = ox + (diameter - fm.stringWidth(s)) / 2;
            int y = oy + (diameter - fm.getHeight()) / 2 + fm.getAscent() - 1;
            g2.drawString(s, x, y);
            g2.dispose();
        }
    }
}
