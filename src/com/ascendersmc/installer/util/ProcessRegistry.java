package com.ascendersmc.installer.util;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ProcessRegistry {
    private record Entry(Process process, String label) {}
    private static final Map<Long, Entry> ACTIVE = new ConcurrentHashMap<>();
    private ProcessRegistry() {}

    public static Process register(Process p) { return register(p, "child-process"); }

    public static Process register(Process p, String label) {
        if (p != null) {
            ACTIVE.put(p.pid(), new Entry(p, label == null ? "child-process" : label));
            InstallerLogger.debug("PROCESS", "register | pid=" + p.pid() + " | label=" + label);
        }
        return p;
    }

    public static void unregister(Process p) {
        if (p != null) {
            Entry removed = ACTIVE.remove(p.pid());
            InstallerLogger.debug("PROCESS", "unregister | pid=" + p.pid() + " | label=" + (removed == null ? "?" : removed.label()));
        }
    }

    public static void shutdownAll() {
        InstallerLogger.debug("PROCESS", "shutdownAll | active=" + ACTIVE.size());
        for (Entry entry : ACTIVE.values()) terminate(entry.process(), Duration.ofSeconds(3));
        ACTIVE.clear();
    }

    public static void terminate(Process p, Duration wait) {
        if (p == null || !p.isAlive()) return;
        InstallerLogger.debug("PROCESS", "terminate.begin | pid=" + p.pid() + " | wait=" + wait);
        var children = p.descendants().toList();
        for (var c : children) if (c.isAlive()) c.destroy();
        p.destroy();
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline && p.isAlive()) {
            try { Thread.sleep(50); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        for (var c : children) if (c.isAlive()) c.destroyForcibly();
        if (p.isAlive()) p.destroyForcibly();
        InstallerLogger.debug("PROCESS", "terminate.end | pid=" + p.pid() + " | alive=" + p.isAlive());
    }
}
