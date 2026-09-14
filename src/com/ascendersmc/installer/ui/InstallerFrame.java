package com.ascendersmc.installer.ui;

import com.ascendersmc.installer.config.InstallerConfig;
import com.ascendersmc.installer.launcher.LauncherDetector;
import com.ascendersmc.installer.model.InstallTarget;
import com.ascendersmc.installer.model.InstallerProfile;
import com.ascendersmc.installer.model.LauncherType;
import com.ascendersmc.installer.service.UniversalInstallService;
import com.ascendersmc.installer.util.InstallerLogger;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

public final class InstallerFrame extends JFrame {
    private final InstallerConfig config;
    private InstallerProfile selectedProfile;
    private InstallTarget selectedTarget;
    private final Map<LauncherType, InstallTarget> detectedTargets = new LinkedHashMap<>();
    private final List<LauncherCard> launcherCards = new ArrayList<>();

    private final JTextArea log = new JTextArea();
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final ModernButton installButton = new ModernButton("INSTALAR / REPARAR");
    private final ModernButton exportLogButton = new ModernButton("GUARDAR LOG", new Color(45, 42, 66), new Color(74, 65, 105));
    private final JLabel launcherValue = valueLabel();
    private final JLabel profileValue = valueLabel();
    private final JLabel minecraftValue = valueLabel();
    private final JLabel neoForgeValue = valueLabel();
    private final JLabel statusValue = valueLabel();
    private final JLabel pathValue = valueLabel();
    private final JLabel topStatus = new JLabel("LISTO", SwingConstants.CENTER);
    private final JLabel selectedLauncherNameLabel = valueLabel();
    private final JLabel selectedLauncherAvailabilityLabel = valueLabel();
    private Icon selectedLauncherIcon;
    private final JComponent selectedLauncherBadge = new JComponent() {
        @Override protected void paintComponent(Graphics g) {
            if (selectedTarget == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selectedLauncherIcon != null) {
                int x = (getWidth() - selectedLauncherIcon.getIconWidth()) / 2;
                int y = (getHeight() - selectedLauncherIcon.getIconHeight()) / 2;
                selectedLauncherIcon.paintIcon(this, g2, x, y);
            } else {
                Color c = selectedTarget.launcherType().accent();
                g2.setColor(c);
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(255,255,255,180));
                g2.drawOval(0,0,getWidth()-1,getHeight()-1);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 24f));
                String s = selectedTarget.launcherType().shortLabel();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(s, (getWidth()-fm.stringWidth(s))/2, (getHeight()-fm.getHeight())/2 + fm.getAscent());
            }
            g2.dispose();
        }
    };
    private final JPanel launcherGrid = new JPanel(new GridLayout(0, 3, 12, 12));
    private final ProfileCard cobbleCard;
    private final ProfileCard pixelCard;

    private Point dragOrigin;
    private int scanGeneration;
    private volatile boolean scanInProgress;
    private Image logo;
    private Image cobbleImage;
    private Image pixelImage;

    public InstallerFrame(InstallerConfig config) {
        this.config = config;
        loadImages();
        setTitle("ASCENDERSMC-UNIVERSAL-INSTALLER");
        setUndecorated(true);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1220, 830));
        setSize(1240, 860);
        setLocationRelativeTo(null);
        setBackground(new Color(0, 0, 0, 0));
        if (logo != null) setIconImage(logo);

        cobbleCard = new ProfileCard(InstallerProfile.COBBLEWORLD, "COBBLEWORLD", cobbleImage);
        pixelCard = new ProfileCard(InstallerProfile.PIXELMON, "PIXELMON", pixelImage);

        setContentPane(new BackgroundPanel());
        setLayout(new BorderLayout());
        build();
        bindActions();
        InstallerLogger.addListener(line -> SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        }));
        InstallerLogger.debug("UI", "InstallerFrame construido | size=" + getWidth() + "x" + getHeight());
        LauncherDetector.warmUpWindowsInventory();
        selectProfile(InstallerProfile.COBBLEWORLD);
    }

    private void build() {
        add(buildTitleBar(), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(18, 18));
        center.setOpaque(false);
        center.setBorder(new EmptyBorder(18, 18, 18, 18));
        center.add(buildLeftColumn(), BorderLayout.WEST);
        center.add(buildRightColumn(), BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
    }

    private JComponent buildTitleBar() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(14, 18, 8, 18));
        JPanel bar = roundedPanel(24, new Color(12, 12, 22, 225), new Color(77, 64, 109, 120));
        bar.setLayout(new BorderLayout());
        bar.setBorder(new EmptyBorder(10, 14, 10, 14));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        if (logo != null) left.add(new JLabel(new ImageIcon(logo.getScaledInstance(32, 32, Image.SCALE_SMOOTH))));
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("ASCENDERSMC-UNIVERSAL-INSTALLER");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        text.add(title);
        left.add(text);
        bar.add(left, BorderLayout.WEST);

        topStatus.setOpaque(true);
        topStatus.setBackground(new Color(24, 38, 56));
        topStatus.setForeground(new Color(90, 255, 180));
        topStatus.setBorder(new EmptyBorder(8, 14, 8, 14));
        topStatus.setFont(topStatus.getFont().deriveFont(Font.BOLD, 12f));
        JPanel statusWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        statusWrap.setOpaque(false);
        statusWrap.add(topStatus);
        statusWrap.add(windowButton("—", () -> setState(Frame.ICONIFIED), new Color(69, 102, 192)));
        statusWrap.add(windowButton("□", () -> setExtendedState((getExtendedState() & Frame.MAXIMIZED_BOTH) != 0 ? Frame.NORMAL : Frame.MAXIMIZED_BOTH), new Color(94, 94, 120)));
        statusWrap.add(windowButton("×", this::dispose, new Color(163, 74, 95)));
        bar.add(statusWrap, BorderLayout.EAST);

        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragOrigin = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                Point p = e.getLocationOnScreen();
                setLocation(p.x - dragOrigin.x, p.y - dragOrigin.y);
            }
        };
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        left.addMouseListener(drag);
        left.addMouseMotionListener(drag);
        text.addMouseListener(drag);
        text.addMouseMotionListener(drag);

        wrap.add(bar, BorderLayout.CENTER);
        return wrap;
    }

    private JComponent buildLeftColumn() {
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setPreferredSize(new Dimension(480, 720));
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(card(buildProfilePanel()));
        left.add(Box.createVerticalStrut(16));
        left.add(card(buildLauncherPanel()));
        left.add(Box.createVerticalGlue());
        left.add(card(buildActionPanel()));
        return left;
    }

    private JComponent buildRightColumn() {
        JPanel right = new JPanel(new BorderLayout(0, 16));
        right.setOpaque(false);
        right.add(card(buildSummaryPanel()), BorderLayout.NORTH);
        right.add(card(buildLogPanel()), BorderLayout.CENTER);
        return right;
    }

    private JComponent buildProfilePanel() {
        JPanel panel = transparentVBox();
        panel.add(section("INSTALAR ASCENDERSMC"));
        panel.add(Box.createVerticalStrut(10));
        panel.add(subtle("MODPACK"));
        panel.add(Box.createVerticalStrut(10));

        JPanel cards = new JPanel();
        cards.setOpaque(false);
        cards.setLayout(new BoxLayout(cards, BoxLayout.X_AXIS));
        cards.setAlignmentX(Component.LEFT_ALIGNMENT);
        cards.setPreferredSize(new Dimension(420, 112));
        cards.setMinimumSize(new Dimension(420, 112));
        cards.setMaximumSize(new Dimension(420, 112));
        cards.add(cobbleCard);
        cards.add(Box.createHorizontalStrut(12));
        cards.add(pixelCard);
        cards.add(Box.createHorizontalGlue());
        panel.add(cards);
        return panel;
    }

    private JComponent buildLauncherPanel() {
        JPanel panel = transparentVBox();
        panel.add(subtle("LAUNCHER / DESTINO"));
        panel.add(Box.createVerticalStrut(10));
        launcherGrid.setOpaque(true);
        launcherGrid.setBackground(new Color(7, 9, 25));
        launcherGrid.setBorder(new EmptyBorder(2, 2, 2, 4));
        JScrollPane scroll = new JScrollPane(launcherGrid);
        scroll.setOpaque(true);
        scroll.setBackground(new Color(7, 9, 25));
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(new Color(7, 9, 25));
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        styleScroll(scroll);
        scroll.setPreferredSize(new Dimension(420, 372));
        panel.add(scroll);
        return panel;
    }

    private JComponent buildActionPanel() {
        JPanel panel = transparentVBox();
        panel.add(section("INSTALACIÓN"));
        panel.add(Box.createVerticalStrut(14));
        installButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        installButton.setPreferredSize(new Dimension(240, 52));
        installButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        panel.add(installButton);
        return panel;
    }

    private JComponent buildSummaryPanel() {
        JPanel panel = new JPanel(new BorderLayout(18, 0));
        panel.setOpaque(false);
        panel.add(buildSelectedLauncherCard(), BorderLayout.WEST);

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        JLabel title = section("RESUMEN DE INSTALACIÓN");
        info.add(title);
        info.add(Box.createVerticalStrut(12));
        info.add(summaryRow("PERFIL", profileValue));
        info.add(Box.createVerticalStrut(8));
        info.add(summaryRow("MINECRAFT", minecraftValue));
        info.add(Box.createVerticalStrut(8));
        info.add(summaryRow("NEOFORGE", neoForgeValue));
        info.add(Box.createVerticalStrut(8));
        info.add(summaryRow("LAUNCHER", launcherValue));
        info.add(Box.createVerticalStrut(8));
        info.add(summaryRow("ESTADO", statusValue));
        info.add(Box.createVerticalStrut(8));
        info.add(summaryRow("RUTA", pathValue));
        panel.add(info, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildSelectedLauncherCard() {
        JPanel card = roundedPanel(22, new Color(19, 24, 44), new Color(71, 70, 109, 100));
        card.setPreferredSize(new Dimension(250, 180));
        card.setLayout(new BorderLayout());
        card.setBorder(new EmptyBorder(18, 18, 18, 18));

        JPanel logoWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        logoWrap.setOpaque(false);
        selectedLauncherBadge.setPreferredSize(new Dimension(84, 84));
        logoWrap.add(selectedLauncherBadge);
        card.add(logoWrap, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel selectedLbl = subtleLabel("LAUNCHER SELECCIONADO");
        selectedLauncherNameLabel.setFont(selectedLauncherNameLabel.getFont().deriveFont(Font.BOLD, 18f));
        selectedLauncherAvailabilityLabel.setForeground(new Color(154, 241, 196));
        text.add(selectedLbl);
        text.add(Box.createVerticalStrut(8));
        text.add(selectedLauncherNameLabel);
        text.add(Box.createVerticalStrut(8));
        text.add(selectedLauncherAvailabilityLabel);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setOpaque(false);

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.add(section("PROGRESO Y REGISTRO"), BorderLayout.WEST);
        exportLogButton.setFont(new Font("Segoe UI", Font.BOLD, 10));
        exportLogButton.setPreferredSize(new Dimension(108, 30));
        exportLogButton.setMinimumSize(new Dimension(108, 30));
        exportLogButton.setMaximumSize(new Dimension(108, 30));
        header.add(exportLogButton, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        log.setEditable(false);
        log.setBackground(new Color(5, 6, 14));
        log.setForeground(new Color(195, 188, 207));
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setMargin(new Insets(12, 12, 12, 12));
        log.setLineWrap(false);
        log.setWrapStyleWord(false);
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(47, 50, 82)));
        styleScroll(scroll);
        panel.add(scroll, BorderLayout.CENTER);
        progress.setStringPainted(true);
        progress.setBorderPainted(false);
        progress.setForeground(new Color(116, 82, 230));
        progress.setBackground(new Color(23, 24, 40));
        progress.setString("0 %");
        panel.add(progress, BorderLayout.SOUTH);
        return panel;
    }

    private void bindActions() {
        installButton.addActionListener(e -> runInstall());
        exportLogButton.addActionListener(e -> exportCurrentLog());
    }

    private void exportCurrentLog() {
        Path source = InstallerLogger.currentLogFile();
        InstallerLogger.debug("UI", "Solicitud de exportar registro | source=" + source);
        String fileName = source == null ? "ASCENDERSMC-Universal-Installer.log" : source.getFileName().toString();

        FileDialog dialog = new FileDialog(this, "Guardar registro ASCENDERSMC", FileDialog.SAVE);
        dialog.setFile(fileName);
        dialog.setDirectory(System.getProperty("user.home"));
        dialog.setVisible(true);
        if (dialog.getFile() == null) {
            InstallerLogger.debug("UI", "Exportación de registro cancelada por el usuario");
            return;
        }
        try {
            Path destination = Path.of(dialog.getDirectory(), dialog.getFile());
            Path saved = InstallerLogger.exportTo(destination);
            UiSoundService.success();
            ModernDialog.showLogExported(this, saved);
        } catch (Exception ex) {
            InstallerLogger.error("LOG", "No se pudo exportar el registro", ex);
            UiSoundService.error();
            ModernDialog.showError(this, "Error al guardar registro", ex.getMessage());
        }
    }

    private void reloadTargetsAsync() {
        final InstallerProfile requestedProfile = selectedProfile;
        final int generation = ++scanGeneration;
        scanInProgress = true;
        installButton.setEnabled(false);
        topStatus.setText("DETECTANDO...");
        topStatus.setForeground(new Color(255, 208, 105));
        InstallerLogger.debug("UI", "Recargando launchers async para perfil=" + requestedProfile.displayName()
                + " | generation=" + generation);

        Thread worker = new Thread(() -> {
            try {
                List<InstallTarget> targets = new LauncherDetector(config).detect(requestedProfile);
                SwingUtilities.invokeLater(() -> {
                    if (generation != scanGeneration || selectedProfile != requestedProfile) {
                        InstallerLogger.debug("UI", "Resultado de scan descartado por cambio de perfil | generation=" + generation);
                        return;
                    }
                    applyTargets(targets);
                    scanInProgress = false;
                });
            } catch (Throwable ex) {
                InstallerLogger.error("DETECT", "Fallo durante inventario/detección", ex);
                SwingUtilities.invokeLater(() -> {
                    if (generation != scanGeneration) return;
                    scanInProgress = false;
                    topStatus.setText("ERROR DETECCIÓN");
                    topStatus.setForeground(new Color(255, 120, 120));
                    ModernDialog.showError(this, "Error de detección", ex.getMessage());
                });
            }
        }, "ascendersmc-launcher-scan-" + requestedProfile.id());
        worker.setDaemon(true);
        worker.start();
    }

    private void applyTargets(List<InstallTarget> targets) {
        InstallerLogger.debug("UI", "Launchers detectados=" + targets.size() + " | perfil=" + selectedProfile.displayName());
        detectedTargets.clear();
        launcherCards.clear();
        launcherGrid.removeAll();
        for (InstallTarget target : targets) {
            detectedTargets.put(target.launcherType(), target);
            LauncherCard card = new LauncherCard(target);
            launcherCards.add(card);
            launcherGrid.add(card);
        }
        int rows = Math.max(1, (targets.size() + 2) / 3);
        int preferredHeight = rows * 110 + Math.max(0, rows - 1) * 12 + 6;
        launcherGrid.setPreferredSize(new Dimension(408, preferredHeight));
        selectedTarget = targets.stream().filter(InstallTarget::installed).findFirst().orElse(targets.isEmpty() ? null : targets.get(0));
        launcherGrid.revalidate();
        launcherGrid.repaint();
        refreshLauncherSelection();
        updateSummary();
    }

    private void selectProfile(InstallerProfile profile) {
        if (profile == selectedProfile && !detectedTargets.isEmpty()) {
            InstallerLogger.debug("UI", "Perfil ya seleccionado; se omite nuevo inventario=" + profile.displayName());
            return;
        }
        InstallerLogger.debug("UI", "Perfil seleccionado=" + profile.displayName());
        this.selectedProfile = profile;
        cobbleCard.setSelectedState(profile == InstallerProfile.COBBLEWORLD);
        pixelCard.setSelectedState(profile == InstallerProfile.PIXELMON);
        profileValue.setText(profile.displayName());
        var cfg = config.profile(profile);
        minecraftValue.setText(cfg.minecraftVersion());
        neoForgeValue.setText(cfg.neoForgeVersion());
        reloadTargetsAsync();
    }

    private void selectLauncher(LauncherType type) {
        if (scanInProgress) return;
        InstallerLogger.debug("UI", "Click launcher=" + type.label());
        InstallTarget target = detectedTargets.get(type);
        if (target == null) return;
        if (!target.installed()) {
            UiSoundService.error();
            ModernDialog.showInfo(this, "Launcher no detectado", type.label() + " no está instalado en esta computadora.");
            return;
        }
        UiSoundService.click();
        selectedTarget = target;
        InstallerLogger.debug("UI", "Launcher seleccionado=" + target.launcherType().label() + " | minecraftDir=" + target.minecraftDir() + " | executable=" + target.launcherExecutable());
        refreshLauncherSelection();
        updateSummary();
    }

    private void refreshLauncherSelection() {
        for (LauncherCard card : launcherCards) card.setSelectedState(selectedTarget != null && card.target.launcherType() == selectedTarget.launcherType());
        updateSummary();
    }

    private void updateSummary() {
        var cfg = config.profile(selectedProfile);
        profileValue.setText(selectedProfile.displayName());
        minecraftValue.setText(cfg.minecraftVersion());
        neoForgeValue.setText(cfg.neoForgeVersion());
        if (selectedTarget == null) {
            launcherValue.setText("—");
            selectedLauncherNameLabel.setText("—");
            selectedLauncherAvailabilityLabel.setText("—");
            selectedLauncherIcon = null;
            selectedLauncherBadge.repaint();
            statusValue.setText("—");
            pathValue.setText("—");
            installButton.setEnabled(false);
            topStatus.setText("SIN LAUNCHER");
            topStatus.setForeground(new Color(255, 123, 123));
            return;
        }
        launcherValue.setText(selectedTarget.launcherType().label());
        selectedLauncherNameLabel.setText(selectedTarget.launcherType().label());
        selectedLauncherAvailabilityLabel.setText(selectedTarget.installed() ? "INSTALADO" : "NO INSTALADO");
        selectedLauncherAvailabilityLabel.setForeground(selectedTarget.installed() ? new Color(120, 235, 170) : new Color(255, 124, 124));
        selectedLauncherIcon = LauncherLogoService.load(selectedTarget, 72);
        selectedLauncherBadge.repaint();
        statusValue.setText(selectedTarget.installed() ? "INSTALADO" : "NO INSTALADO");
        statusValue.setForeground(selectedTarget.installed() ? new Color(120, 235, 170) : new Color(255, 124, 124));
        pathValue.setText(toHtmlPath(selectedTarget.minecraftDir().toString()));
        installButton.setEnabled(selectedTarget.installed());
        topStatus.setText(selectedTarget.installed() ? "LISTO" : "NO INSTALADO");
        topStatus.setForeground(selectedTarget.installed() ? new Color(90, 255, 180) : new Color(255, 125, 125));
    }

    private String toHtmlPath(String path) {
        if (path.length() <= 64) return "<html>" + path + "</html>";
        String cut = path.substring(0, 22) + "..." + path.substring(Math.max(22, path.length() - 40));
        return "<html>" + cut + "</html>";
    }


    private String fitText(FontMetrics fm, String text, int maxWidth) {
        if (text == null || text.isBlank()) return "";
        if (fm.stringWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int target = Math.max(10, maxWidth - fm.stringWidth(ellipsis));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (fm.stringWidth(sb.toString() + c) > target) break;
            sb.append(c);
        }
        return sb + ellipsis;
    }

    private void runInstall() {
        if (selectedTarget == null || !selectedTarget.installed()) {
            InstallerLogger.debug("UI", "Instalación ignorada: no hay launcher instalado seleccionado");
            return;
        }
        InstallerLogger.debug("UI", "Inicio solicitado | profile=" + selectedProfile.displayName() + " | launcher=" + selectedTarget.launcherType().label() + " | dir=" + selectedTarget.minecraftDir());
        installButton.setEnabled(false);
        progress.setValue(0);
        progress.setString("Preparando...");
        UiSoundService.click();
        Thread worker = new Thread(() -> {
            try {
                var registration = new UniversalInstallService(config).install(selectedProfile, selectedTarget, (pct, msg) -> SwingUtilities.invokeLater(() -> {
                    progress.setValue(pct);
                    progress.setString(msg);
                }));
                InstallerLogger.debug("UI", "Instalación finalizada correctamente | importRequired=" + registration.importRequired()
                        + " | apertura automática del launcher deshabilitada");
                SwingUtilities.invokeLater(() -> {
                    UiSoundService.success();
                    ModernDialog.showInstallationCompleted(this, selectedProfile, selectedTarget, registration);
                });
            } catch (Exception ex) {
                InstallerLogger.error("INSTALL", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> {
                    UiSoundService.error();
                    ModernDialog.showError(this, "Error de instalación", ex.getMessage());
                });
            } finally {
                SwingUtilities.invokeLater(() -> installButton.setEnabled(selectedTarget != null && selectedTarget.installed()));
            }
        }, "ascendersmc-installer-main");
        worker.setDaemon(true);
        worker.start();
    }

    private JPanel transparentVBox() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 17.5f));
        return label;
    }

    private JLabel subtle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(190, 183, 209));
        label.setFont(label.getFont().deriveFont(12f));
        return label;
    }

    private JLabel subtleLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(164, 158, 187));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11.5f));
        return label;
    }

    private JLabel valueLabel() {
        JLabel label = new JLabel();
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13.5f));
        return label;
    }

    private JComponent summaryRow(String key, JLabel value) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel keyLabel = subtleLabel(key);
        keyLabel.setPreferredSize(new Dimension(90, 18));
        row.add(keyLabel, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private JPanel card(JComponent inner) {
        JPanel p = roundedPanel(28, new Color(7, 9, 25, 210), new Color(56, 60, 96, 110));
        p.setLayout(new BorderLayout());
        p.setBorder(new EmptyBorder(20, 20, 20, 20));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    private JPanel roundedPanel(int arc, Color background, Color border) {
        return new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(background);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.dispose();
            }
        };
    }

    private JButton windowButton(String text, Runnable action, Color hover) {
        JButton button = new JButton(text);
        button.setForeground(Color.WHITE);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 18f));
        button.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> action.run());
        button.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { button.setOpaque(true); button.setBackground(hover); }
            @Override public void mouseExited(MouseEvent e) { button.setOpaque(false); button.setBackground(new Color(0,0,0,0)); }
        });
        return button;
    }

    private void styleScroll(JScrollPane scroll) {
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getHorizontalScrollBar().setOpaque(false);
        scroll.getVerticalScrollBar().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setUI(new ModernScrollbarUI(false));
        scroll.getHorizontalScrollBar().setUI(new ModernScrollbarUI(true));
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(10, Integer.MAX_VALUE));
        scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(Integer.MAX_VALUE, 10));
        scroll.setCorner(ScrollPaneConstants.LOWER_RIGHT_CORNER, new JPanel());
    }

    private void loadImages() {
        try {
            logo = loadImage("/assets/logo.png");
            cobbleImage = loadImage("/assets/profile-cobbleworld.png");
            pixelImage = loadImage("/assets/profile-pixelmon.png");
        } catch (Exception ignored) {}
    }

    private Image loadImage(String path) throws IOException {
        try (InputStream in = InstallerFrame.class.getResourceAsStream(path)) {
            if (in == null) return null;
            BufferedImage img = ImageIO.read(in);
            return img;
        }
    }

    private final class ProfileCard extends JPanel {
        private final InstallerProfile profile;
        private final String title;
        private final Image image;
        private boolean selected;

        private ProfileCard(InstallerProfile profile, String title, Image image) {
            this.profile = profile;
            this.title = title;
            this.image = image;
            setOpaque(false);
            Dimension fixed = new Dimension(204, 112);
            setPreferredSize(fixed);
            setMinimumSize(fixed);
            setMaximumSize(fixed);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { UiSoundService.click(); selectProfile(profile); }
            });
        }

        void setSelectedState(boolean selected) { this.selected = selected; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(selected ? new Color(67, 50, 126) : new Color(21, 24, 38));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
            g2.setColor(selected ? new Color(129, 99, 246) : new Color(56, 60, 96));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            if (image != null) {
                Shape clip = g2.getClip();
                g2.setClip(new RoundRectangle2D.Float(14, 20, 58, 58, 14, 14));
                g2.drawImage(image.getScaledInstance(58, 58, Image.SCALE_SMOOTH), 14, 20, null);
                g2.setClip(clip);
            }
            int textX = 84;
            int textWidth = Math.max(40, getWidth() - textX - 14);
            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, 13.0f));
            FontMetrics titleMetrics = g2.getFontMetrics();
            g2.drawString(fitText(titleMetrics, title, textWidth), textX, 45);
            g2.setColor(selected ? new Color(150, 240, 178) : new Color(150, 145, 170));
            g2.setFont(getFont().deriveFont(Font.BOLD, 10.3f));
            FontMetrics stateMetrics = g2.getFontMetrics();
            String state = selected ? "SELECCIONADO" : "NO SELECCIONADO";
            g2.drawString(fitText(stateMetrics, state, textWidth), textX, 65);
            if (selected) {
                g2.setColor(new Color(92, 255, 173));
                g2.fillOval(getWidth() - 20, 14, 10, 10);
            }
            g2.dispose();
        }
    }

    private final class ModernScrollbarUI extends BasicScrollBarUI {
        private final boolean horizontal;

        private ModernScrollbarUI(boolean horizontal) {
            this.horizontal = horizontal;
        }

        @Override protected void configureScrollBarColors() {
            thumbColor = new Color(124, 88, 236);
            trackColor = new Color(18, 20, 34);
        }

        @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
        @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }

        private JButton zeroButton() {
            JButton b = new JButton();
            b.setOpaque(false);
            b.setContentAreaFilled(false);
            b.setBorder(BorderFactory.createEmptyBorder());
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(16, 18, 30));
            g2.fillRoundRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height, 10, 10);
            g2.dispose();
        }

        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int pad = 1;
            int arc = 10;
            int x = thumbBounds.x + pad;
            int y = thumbBounds.y + pad;
            int w = Math.max(6, thumbBounds.width - pad * 2);
            int h = Math.max(6, thumbBounds.height - pad * 2);
            GradientPaint gp = horizontal
                    ? new GradientPaint(x, y, new Color(138, 104, 247), x, y + h, new Color(104, 70, 217))
                    : new GradientPaint(x, y, new Color(138, 104, 247), x + w, y, new Color(104, 70, 217));
            g2.setPaint(gp);
            g2.fillRoundRect(x, y, w, h, arc, arc);
            g2.setColor(new Color(180, 154, 255, 150));
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.dispose();
        }
    }

    private final class LauncherCard extends JPanel {
        private final InstallTarget target;
        private boolean selected;
        private Icon launcherIcon;

        private LauncherCard(InstallTarget target) {
            this.target = target;
            this.launcherIcon = LauncherLogoService.load(target, 42);
            setOpaque(false);
            setPreferredSize(new Dimension(132, 110));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { selectLauncher(target.launcherType()); }
            });
        }

        void setSelectedState(boolean selected) { this.selected = selected; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color border = !target.installed() ? new Color(130, 46, 58) : selected ? new Color(127, 98, 246) : new Color(55, 59, 88);
            Color bg = !target.installed() ? new Color(34, 15, 22) : selected ? new Color(41, 31, 78) : new Color(17, 20, 35);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            g2.setColor(border);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);

            g2.setColor(new Color(255, 255, 255, target.installed() ? 12 : 7));
            g2.fillRoundRect(10, 10, 46, 46, 14, 14);

            if (launcherIcon != null) {
                int ix = 12 + (42 - launcherIcon.getIconWidth()) / 2;
                int iy = 12 + (42 - launcherIcon.getIconHeight()) / 2;
                launcherIcon.paintIcon(this, g2, ix, iy);
            } else {
                g2.setColor(target.launcherType().accent());
                g2.fillOval(14, 14, 38, 38);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
                String shortTxt = target.launcherType().shortLabel();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(shortTxt, 14 + (38 - fm.stringWidth(shortTxt)) / 2, 39);
            }

            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, 12.1f));
            FontMetrics titleFm = g2.getFontMetrics();
            String title = fitText(titleFm, target.launcherType().label(), getWidth() - 24);
            g2.drawString(title, 12, 72);

            g2.setColor(target.installed() ? new Color(120, 235, 170) : new Color(255, 108, 108));
            g2.setFont(getFont().deriveFont(Font.BOLD, 10.5f));
            FontMetrics stateFm = g2.getFontMetrics();
            String state = fitText(stateFm, target.availabilityText(), getWidth() - 24);
            g2.drawString(state, 12, 89);

            if (selected) {
                g2.setColor(new Color(92, 255, 173));
                g2.fillOval(getWidth() - 18, 12, 8, 8);
            }
            g2.dispose();
        }
    }

    private static final class BackgroundPanel extends JPanel {
        private BackgroundPanel() { setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = getHeight();
            GradientPaint gp = new GradientPaint(0, 0, new Color(7, 7, 18), 0, h, new Color(4, 4, 12));
            g2.setPaint(gp);
            g2.fillRect(0, 0, w, h);
            g2.setColor(new Color(78, 52, 155, 60));
            g2.fillOval(-120, -60, 420, 420);
            g2.setColor(new Color(30, 84, 174, 32));
            g2.fillOval(w - 360, h - 300, 420, 420);
            g2.dispose();
        }
    }
}
