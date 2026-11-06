package com.forgeward.stopguard;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Stop guard. A dedicated-server stop can hang AFTER every save: for example, with Distant Horizons' generatorPlan
 * SURFACE_THEN_CHUNKS a non-daemon "DH-World Gen Thread" can park forever in vanilla Blender -> IOWorker
 * CompletableFuture.join (seen in jstacks), and a non-daemon thread keeps the JVM alive, so the process never exits and a
 * host or restart script has to force-kill it.
 *
 * MinecraftServer.runServer (NeoForge 21.1.250, javap of the patched class): handleServerStopping -> stopServer()
 * (every level saved, "All dimensions are saved") -> handleServerStopped (posts ServerStoppedEvent) -> onServerExit().
 * So ServerStoppedEvent is strictly after the saves. Only then a DAEMON watchdog starts: if the JVM is still alive
 * {@code exitAfterSeconds} later it logs the non-daemon threads holding it (name, state, top frames) and calls
 * System.exit(0) (runs the shutdown hooks; a stuck non-daemon thread doesn't block it); if the JVM is STILL alive
 * {@code haltAfterSeconds} after that, Runtime.halt(0). A clean exit never sees it fire: the watchdog is a daemon and
 * dies with the JVM; a shutdown hook then logs "not needed". Dedicated servers only: an integrated (single-player)
 * server posts the same event when a world closes, and the client must live on.
 */
@Mod(StopGuard.MODID)
public final class StopGuard {
    public static final String MODID = "forgeward_stopguard";
    private static final Logger LOG = LogUtils.getLogger();

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED, TEST_STUCK_THREAD;
    static final ModConfigSpec.IntValue EXIT_AFTER, HALT_AFTER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Arm the watchdog after a server stop.").define("enabled", true);
        EXIT_AFTER = b.comment("Seconds after ServerStoppedEvent (all levels saved) before System.exit(0) if the JVM is still up.").defineInRange("exitAfterSeconds", 15, 5, 600);
        HALT_AFTER = b.comment("Further seconds before Runtime.halt(0) if System.exit itself hangs (a stuck shutdown hook).").defineInRange("haltAfterSeconds", 30, 5, 600);
        TEST_STUCK_THREAD = b.comment("Check the watchdog on your own server: at the next stop, start a non-daemon thread that never ends, so you can see the watchdog fire. Leave false in normal use.").define("testSpawnStuckThread", false);
        SPEC = b.build();
    }

    private static final AtomicBoolean ARMED = new AtomicBoolean(), FIRED = new AtomicBoolean();
    private static volatile long stoppedAt;

    public StopGuard(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> LOG.info("[forgeward_stopguard] {}", status(e.getServer())));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> onStopped(e.getServer()));
    }

    private static String status(MinecraftServer server) {
        if (!server.isDedicatedServer() || !FMLEnvironment.dist.isDedicatedServer()) return "not armed: not a dedicated server";
        if (!ENABLED.get()) return "OFF (config)";
        int ex = EXIT_AFTER.get(), halt = HALT_AFTER.get();
        return "armed: exit after " + ex + " s, halt after " + (ex + halt) + " s (counted from ServerStoppedEvent, after all saves)"
                + (TEST_STUCK_THREAD.get() ? "; TEST stuck thread ON" : "");
    }

    private static void onStopped(MinecraftServer server) {
        if (!server.isDedicatedServer() || !FMLEnvironment.dist.isDedicatedServer() || !ENABLED.get()) return;
        if (!ARMED.compareAndSet(false, true)) return;
        stoppedAt = System.nanoTime();
        final long exitMs = EXIT_AFTER.get() * 1000L, haltMs = HALT_AFTER.get() * 1000L;
        if (TEST_STUCK_THREAD.get()) {
            Thread stuck = new Thread(() -> { while (true) LockSupport.park(); }, "stopguard-test-stuck");
            stuck.setDaemon(false);
            stuck.start();
            say("TEST: started a stuck non-daemon thread \"stopguard-test-stuck\"");
        }
        // a clean exit: this hook runs and the daemon watchdog simply dies with the JVM
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!FIRED.get()) say("JVM exiting normally " + sinceMs() + " ms after the server stopped: not needed");
        }, "stopguard-exit-note"));
        Thread dog = new Thread(() -> {
            try { Thread.sleep(exitMs); } catch (InterruptedException ie) { return; }
            FIRED.set(true);
            List<String> holders = holders();
            say("JVM still up " + sinceMs() + " ms after the server stopped (all saved); non-daemon threads holding it: " + holders.size()
                    + " " + names(holders) + "; System.exit(0)", true);
            for (String h : holders) say("  holder " + h, true);
            Thread halter = new Thread(() -> {
                try { Thread.sleep(haltMs); } catch (InterruptedException ie) { return; }
                say("System.exit did not finish within " + haltMs / 1000 + " s (" + sinceMs() + " ms after stop): Runtime.halt(0)", true);
                Runtime.getRuntime().halt(0);
            }, "stopguard-halt");
            halter.setDaemon(true);
            halter.start();
            System.exit(0);
        }, "stopguard-watchdog");
        dog.setDaemon(true);
        dog.start();
        say("server stopped (all levels saved): watchdog started, System.exit(0) in " + exitMs / 1000 + " s if the JVM is still up");
    }

    /** live non-daemon threads other than the JVM's own DestroyJavaVM waiter, with state and top frames */
    private static List<String> holders() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t.isDaemon() || !t.isAlive() || t == Thread.currentThread() || "DestroyJavaVM".equals(t.getName())) continue;
            StringBuilder b = new StringBuilder("\"").append(t.getName()).append("\" ").append(t.getState());
            StackTraceElement[] st = e.getValue();
            for (int i = 0; i < Math.min(8, st.length); i++) b.append(" | at ").append(st[i]);
            out.add(b.toString());
        }
        return out;
    }

    private static String names(List<String> holders) {
        List<String> n = new ArrayList<>();
        for (String h : holders) n.add(h.substring(0, h.indexOf('"', 1) + 1));
        return n.toString();
    }

    private static long sinceMs() { return (System.nanoTime() - stoppedAt) / 1_000_000; }

    /** log4j may be shutting down by now: the line also goes to stdout, which the console log keeps */
    private static void say(String msg) { say(msg, false); }

    private static void say(String msg, boolean warn) {
        String line = "[forgeward_stopguard] " + msg;
        try { if (warn) LOG.warn(line); else LOG.info(line); } catch (Throwable ignored) { }
        System.out.println(line);
        System.out.flush();
    }
}
