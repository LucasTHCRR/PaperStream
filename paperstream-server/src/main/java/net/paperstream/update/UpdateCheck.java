package net.paperstream.update;

import com.mojang.logging.LogUtils;
import de.empireblocks.updaterapi.AutoUpdater;
import de.empireblocks.updaterapi.UpdateNotifier;
import de.empireblocks.updaterapi.UpdateVersion;
import de.empireblocks.updaterapi.UpdaterAPI;
import de.empireblocks.updaterapi.provider.GitHubReleasesProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import net.paperstream.PaperStreamConfig;
import org.slf4j.Logger;

public final class UpdateCheck {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final File UPDATE_FOLDER = new File("paperstream-update");

    private static ScheduledExecutorService executor;
    private static volatile Path staged;
    private static volatile String stagedVersion;

    private UpdateCheck() {
    }

    public static synchronized void start() {
        if (executor != null || !PaperStreamConfig.updateCheck) {
            return;
        }
        final String version = ownVersion();
        if (version == null) {
            return;
        }
        final String mc = io.papermc.paper.ServerBuildInfo.buildInfo().minecraftVersionId();
        // 26.1.x heisst im Release nur 26.1
        final String mcName = mc.matches("26\\.1\\.\\d+") ? "26.1" : mc;
        final boolean beta = version.toUpperCase(Locale.ROOT).matches(".*-(BETA|ALPHA).*");
        final String userAgent = "PaperStream/" + version;
        final Pattern asset = Pattern.compile(Pattern.quote("paperstream-" + mcName + "-") + "\\d.*\\.jar");
        final UpdaterAPI api = UpdaterAPI.builder(version, userAgent)
            .addProvider(new GitHubReleasesProvider("LucasTHCRR", "PaperStream", userAgent, beta, asset))
            .minecraftVersion(mc)
            .acceptedTypes(beta ? Set.of("release", "beta") : Set.of("release"))
            .build();
        final AutoUpdater auto = new AutoUpdater(api, userAgent);

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "PaperStream Update Check");
            t.setDaemon(true);
            return t;
        });
        final UpdateNotifier notifier = UpdateNotifier.builder(api)
            .executor(executor)
            .initialDelay(Duration.ofSeconds(20))
            .interval(Duration.ofHours(6))
            .onUpdate(v -> found(v, auto))
            .build();
        executor.scheduleWithFixedDelay(notifier::tick, 20, 60, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(UpdateCheck::install, "PaperStream Update Install"));
    }

    private static void found(final UpdateVersion v, final AutoUpdater auto) {
        LOGGER.warn("PaperStream {} is available: https://github.com/LucasTHCRR/PaperStream/releases/tag/v{}", v.versionNumber(), v.versionNumber());
        if (!PaperStreamConfig.updateAutoDownload) {
            LOGGER.warn("Turn on updates.auto-download in paperstream.yml to install it on the next stop.");
            return;
        }
        final File jar = serverJar();
        if (jar == null) {
            LOGGER.warn("Could not find the server jar, please replace it by hand.");
            return;
        }
        final AutoUpdater.Result result = auto.stage(v, jar, UPDATE_FOLDER);
        switch (result.status()) {
            case STAGED, ALREADY_STAGED -> {
                staged = Path.of(result.detail());
                stagedVersion = result.version();
                LOGGER.warn("PaperStream {} is downloaded and replaces {} when the server stops.", result.version(), jar.getName());
            }
            case FAILED -> LOGGER.warn("Downloading PaperStream {} failed: {}", v.versionNumber(), result.detail());
            default -> {
            }
        }
    }

    private static void install() {
        final Path from = staged;
        final File jar = serverJar();
        if (from == null || jar == null || !Files.isRegularFile(from)) {
            return;
        }
        try {
            Files.move(from, jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[PaperStream] Installed PaperStream " + stagedVersion + " as " + jar.getName() + ", it runs from the next start.");
        } catch (final Exception e) {
            // Windows sperrt die laufende Jar
            System.out.println("[PaperStream] Could not replace " + jar.getName() + " (" + e + "). The new jar is in " + from.toAbsolutePath() + ", copy it over by hand.");
        }
    }

    private static File serverJar() {
        final String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty() || cp.contains(File.pathSeparator) || !cp.endsWith(".jar")) {
            return null;
        }
        final File f = new File(cp).getAbsoluteFile();
        return f.isFile() ? f : null;
    }

    private static String ownVersion() {
        try {
            final java.net.URL src = UpdateCheck.class.getProtectionDomain().getCodeSource().getLocation();
            try (JarFile jar = new JarFile(new File(src.toURI()))) {
                final String v = jar.getManifest().getMainAttributes().getValue("PaperStream-Version");
                if (v == null || v.isBlank() || v.equals("dev")) {
                    return null;
                }
                return v.startsWith("v") ? v.substring(1) : v;
            }
        } catch (final Exception e) {
            return null;
        }
    }
}
