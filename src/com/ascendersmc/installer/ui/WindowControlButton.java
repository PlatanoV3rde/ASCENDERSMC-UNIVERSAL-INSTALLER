package com.ascendersmc.installer.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

final class WindowControlButton extends JButton {
    enum Kind { MINIMIZE, MAXIMIZE, CLOSE }

    private static final Color FG = new Color(194, 201, 218);
    private static final Color HOVER = new Color(145, 116, 255, 36);
    private static final Color CLOSE_HOVER = new Color(232, 72, 92, 190);

    private final Kind kind;
    private boolean hover;

    WindowControlButton(Kind kind) {
        this.kind = kind;
        setPreferredSize(new Dimension(38, 34));
        setMinimumSize(new Dimension(38, 34));
        setMaximumSize(new Dimension(38, 34));
        setBorderPainted(false);
        setFocusPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setFocusable(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(switch (kind) {
            case MINIMIZE -> "Minimizar";
            case MAXIMIZE -> "Maximizar / restaurar";
            case CLOSE -> "Cerrar";
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
        });
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (hover) {
                g2.setColor(kind == Kind.CLOSE ? CLOSE_HOVER : HOVER);
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 11, 11);
            }
            g2.setColor(hover && kind == Kind.CLOSE ? Color.WHITE : FG);
            g2.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            switch (kind) {
                case MINIMIZE -> g2.drawLine(cx - 6, cy + 4, cx + 6, cy + 4);
                case MAXIMIZE -> g2.drawRoundRect(cx - 6, cy - 6, 12, 12, 2, 2);
                case CLOSE -> {
                    g2.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                    g2.drawLine(cx + 5, cy - 5, cx - 5, cy + 5);
                }
            }
        } finally { g2.dispose(); }
    }
}
