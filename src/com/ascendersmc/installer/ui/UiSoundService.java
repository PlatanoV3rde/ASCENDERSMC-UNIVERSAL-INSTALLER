package com.ascendersmc.installer.ui;

import javax.sound.sampled.*;

public final class UiSoundService {
    private UiSoundService() {}

    public static void click() { tone(520, 36, 0.055); }

    public static void success() {
        startDaemon("ascendersmc-ui-success", () -> {
            playTone(620, 70, 0.05);
            try { Thread.sleep(45); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            playTone(820, 90, 0.045);
        });
    }

    public static void error() { tone(220, 120, 0.045); }

    private static void tone(double hz, int ms, double volume) {
        startDaemon("ascendersmc-ui-tone", () -> playTone(hz, ms, volume));
    }

    private static void playTone(double hz, int ms, double volume) {
        try {
            float rate = 44100;
            int samples = (int) (rate * ms / 1000.0);
            byte[] data = new byte[samples * 2];
            for (int i = 0; i < samples; i++) {
                double env = Math.sin(Math.PI * i / Math.max(1, samples - 1));
                short v = (short) (Math.sin(2 * Math.PI * hz * i / rate) * Short.MAX_VALUE * volume * env);
                data[i * 2] = (byte) (v & 255);
                data[i * 2 + 1] = (byte) ((v >> 8) & 255);
            }
            AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                line.write(data, 0, data.length);
                line.drain();
            }
        } catch (Exception ignored) {
            // El audio es decorativo; nunca debe impedir que el installer funcione.
        }
    }

    private static void startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
