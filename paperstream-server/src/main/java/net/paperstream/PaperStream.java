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

    private PaperStream() {
    }

    /**
     * Called from DedicatedServer#initServer after the Paper config is loaded and before any world loads.
     */
    public static void init(final MinecraftServer server) {
        PaperStreamConfig.load();
        applyConfig();
        net.paperstream.command.PropertiesReloadHook.snapshot();
        server.server.getCommandMap().register("paperstream", "PaperStream", new PaperStreamCommand("paperstream"));
        server.addTickable(Pregenerator::tick);
    }

    public static void applyConfig() {
        final String compression = PaperStreamConfig.regionCompression.trim().toLowerCase(java.util.Locale.ROOT);
        if (!compression.equals("keep")) {
            RegionFileVersion.configure(compression);
        }
        PlayerChunkPrefetch.applyConfig();
        LOGGER.info("PaperStream: region compression {}, directional loading {}, prefetch {}",
            compression, PaperStreamConfig.directionalLoading, PaperStreamConfig.prefetch);
    }
}
