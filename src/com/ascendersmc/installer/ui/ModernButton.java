package com.ascendersmc.installer.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class ModernButton extends JButton {
    private final Color normal;
    private final Color hoverColor;
    private float hoverAmount;
    private float targetHover;
    private float pressAmount;
    private float targetPress;
    private final Timer animationTimer;

    public ModernButton(String text) {
        this(text, new Color(120, 88, 255), new Color(148, 112, 255));
    }

    public ModernButton(String text, Color normal, Color hoverColor) {
        super(text);
        this.normal = normal;
        this.hoverColor = hoverColor;
        setForeground(Color.WHITE);
        setFont(new Font("Segoe UI", Font.BOLD, 12));
        setBorderPainted(false);
        setFocusPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(220, 50));

        animationTimer = new Timer(16, e -> animateStep());
        animationTimer.setCoalesce(true);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { targetHover = 1f; startAnimation(); }
            @Override public void mouseExited(MouseEvent e) { targetHover = 0f; targetPress = 0f; startAnimation(); }
            @Override public void mousePressed(MouseEvent e) { if (isEnabled()) { targetPress = 1f; startAnimation(); } }
            @Override public void mouseReleased(MouseEvent e) { targetPress = 0f; startAnimation(); }
        });
    }

    private void startAnimation() { if (!animationTimer.isRunning()) animationTimer.start(); }

    private void animateStep() {
        hoverAmount += (targetHover - hoverAmount) * 0.22f;
        pressAmount += (targetPress - pressAmount) * 0.28f;
        if (Math.abs(targetHover - hoverAmount) < 0.01f) hoverAmount = targetHover;
        if (Math.abs(targetPress - pressAmount) < 0.01f) pressAmount = targetPress;
        repaint();
        if (hoverAmount == targetHover && pressAmount == targetPress) animationTimer.stop();
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color base = isEnabled() ? mix(normal, hoverColor, hoverAmount) : new Color(53, 58, 72);
        Color end = isEnabled()
                ? new Color(Math.min(255, base.getRed() + 22), Math.min(255, base.getGreen() + 10), Math.min(255, base.getBlue() + 14))
                : base;
        int inset = Math.round(pressAmount * 2f);
        g2.setPaint(new GradientPaint(0, 0, base, getWidth(), getHeight(), end));
        g2.fillRoundRect(inset, inset, getWidth() - inset * 2, getHeight() - inset * 2, 18, 18);
        if (isEnabled() && hoverAmount > 0.02f) {
            int alpha = Math.min(44, Math.round(34f * hoverAmount));
            g2.setColor(new Color(255, 255, 255, alpha));
            g2.drawRoundRect(inset, inset, getWidth() - 1 - inset * 2, getHeight() - 1 - inset * 2, 18, 18);
        }
        g2.dispose();
        super.paintComponent(g);
    }

    private static Color mix(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }
}
