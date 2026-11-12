package net.paperstream.report;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.paperstream.PaperStreamConfig;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;

// Bemerkt Probleme, hält einen Bericht davon bereit und sagt es der Konsole. Sendet nie von selbst.
public final class ReportHint {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long COOLDOWN_MILLIS = 10L * 60L * 1000L;
    private static final int LAG_SECONDS = 30;
    private static final double LONG_TICK_MS = 2000.0D;

    private static ServerReport captured;
    private static long lastHintMillis;
    private static long lastSendMillis;
    private static boolean sending;
    private static int tickCounter;
    private static int lagSeconds;

    private ReportHint() {
    }

    public static void tick(final MinecraftServer server) {
        if (!PaperStreamConfig.reportHint || ++tickCounter % 20 != 0) {
            return;
        }
        boolean lagging = false;
        boolean longTick = false;
        for (final ServerLevel level : server.getAllLevels()) {
            lagging |= level.psTickTimes.average5s() > PaperStreamConfig.reportHintMspt;
            longTick |= level.psTickTimes.peak5s() > LONG_TICK_MS;
        }
        lagSeconds = lagging ? lagSeconds + 1 : 0;
        final String reason = lagSeconds >= LAG_SECONDS ? "lag" : longTick ? "spike" : null;
        if (reason == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        // hoechstens ein Hinweis alle 10 Minuten
        if (now - lastHintMillis < COOLDOWN_MILLIS) {
            return;
        }
        lastHintMillis = now;
        lagSeconds = 0;
        captured = ServerReport.collect(server, reason);
        LOGGER.warn("PaperStream noticed {}. A report of this moment is ready for 10 minutes, nothing was sent. Look at it with /paperstream report, send it with /paperstream report send.",
            reason.equals("lag") ? "lasting lag" : "a very long tick");
    }

    private static ServerReport current(final MinecraftServer server) {
        if (captured != null && System.currentTimeMillis() - captured.capturedAtMillis > COOLDOWN_MILLIS) {
            captured = null;
        }
        return captured != null ? captured : ServerReport.collect(server, "manual");
    }

    public static void preview(final MinecraftServer server, final CommandSender sender) {
        final ServerReport report = current(server);
        sender.sendMessage("PaperStream report (" + (report == captured
            ? "captured " + (System.currentTimeMillis() - report.capturedAtMillis) / 1000L + " s ago, reason " + report.reason : "current state") + "):");
        sender.sendMessage("  server: PaperStream, Minecraft and Java version, profile, cores, memory, worker threads, player count");
        sender.sendMessage("  " + report.worlds + " worlds as world_1, world_2, ... with type, MSPT, chunks, entity and block entity counts by type");
        sender.sendMessage("  " + report.settings + " settings that differ from the profile, " + report.plugins + " plugins with version");
        sender.sendMessage("  not included: world names, player names, addresses, UUIDs, seeds, coordinates");
        sender.sendMessage("Nothing was sent. Send it to " + PaperStreamConfig.reportUrl + " with /paperstream report send.");
    }

    public static void send(final MinecraftServer server, final CommandSender sender) {
        final long now = System.currentTimeMillis();
        if (sending) {
            sender.sendMessage("A report is being sent right now.");
            return;
        }
        if (lastSendMillis != 0L && now - lastSendMillis < COOLDOWN_MILLIS) {
            sender.sendMessage("The last report was sent " + (now - lastSendMillis) / 60000L + " minutes ago. Wait 10 minutes between reports.");
            return;
        }
        final ServerReport report = current(server);
        sending = true;
        sender.sendMessage("Sending the report...");
        report.send(server, (ok, readUrl, deleteKey, error) -> {
            sending = false;
            if (!ok) {
                sender.sendMessage("The report was not sent: " + error);
                return;
            }
            lastSendMillis = System.currentTimeMillis();
            captured = null;
            sender.sendMessage("Report sent. Link to view it: " + readUrl);
            sender.sendMessage("ID and delete key (keep them, they are shown only once): " + deleteKey);
        });
    }
}
