package net.paperstream;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.paperstream.chunk.PlayerChunkPrefetch;
import net.paperstream.chunk.Pregenerator;
import net.paperstream.command.PaperStreamCommand;
import org.slf4j.Logger;

public final class PaperStream {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private static final String[] LOGO = {
        " ____                         ____   _",
        "|  _ \\ __ _ _ __   ___ _ __  / ___| | |_  _ __   ___   __ _  _ __ ___",
        "| |_) / _` | '_ \\ / _ \\ '__| \\___ \\ | __|| '__| / _ \\ / _` || '_ ` _ \\",
        "|  __/ (_| | |_) |  __/ |     ___) || |_ | |   |  __/| (_| || | | | | |",
        "|_|   \\__,_| .__/ \\___|_|    |____/  \\__||_|    \\___| \\__,_||_| |_| |_|",
        "           |_|",
        "                                                      made by LucasTHCR"
    };

    private PaperStream() {
    }

    /**
     * Start banner, always shown: PaperStream must never be mistaken for an official PaperMC build.
     */
    private static void printBanner() {
        LOGGER.info("------------------------------------------------------------------------");
        for (final String line : LOGO) {
            LOGGER.info(line);
        }
        LOGGER.info("");
        LOGGER.info("PaperStream {} - NOT OFFICIAL PAPER / not affiliated with PaperMC",
            io.papermc.paper.ServerBuildInfo.buildInfo().asString(io.papermc.paper.ServerBuildInfo.StringRepresentation.VERSION_SIMPLE));
        LOGGER.info("Please report problems to PaperStream, not to PaperMC: https://dc.gg/paperstream");
        LOGGER.info("------------------------------------------------------------------------");
    }

    /**
     * Called from DedicatedServer#initServer after the Paper config is loaded and before any world loads.
     */
    private static boolean bannerShown;

    public static void init(final MinecraftServer server) {
        PaperStreamConfig.load();
        applyConfig();
        net.paperstream.command.PropertiesReloadHook.snapshot();
        server.server.getCommandMap().register("paperstream", "PaperStream", new PaperStreamCommand("paperstream"));
        // the first tick runs right after "Done (...)!", so the banner is the last thing of the startup log
        server.addTickable(() -> {
            if (!bannerShown) {
                bannerShown = true;
                printBanner();
                net.paperstream.migrate.PluginCheck.run();
            }
        });
        server.addTickable(Pregenerator::tick);
        net.paperstream.link.ProxyLink.init(server);
        server.addTickable(net.paperstream.link.ProxyLink::tick);
    }

    public static void applyConfig() {
        final String compression = PaperStreamConfig.regionCompression.trim().toLowerCase(java.util.Locale.ROOT);
        if (!compression.equals("keep")) {
            RegionFileVersion.configure(compression);
        }
        PlayerChunkPrefetch.applyConfig();
        io.papermc.paper.FeatureHooks.applyPlayerChunkUnloadDelay();
        LOGGER.info("PaperStream: profile {}, region compression {}, directional loading {}, prefetch {}",
            PaperStreamConfig.profile.id, compression, PaperStreamConfig.directionalLoading, PaperStreamConfig.prefetch);
    }
}
