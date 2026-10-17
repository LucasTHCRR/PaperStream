package net.paperstream.command;

/** Public entry point so startup code outside this package can take the properties snapshot. */
public final class PropertiesReloadHook {

    private PropertiesReloadHook() {
    }

    public static void snapshot() {
        PropertiesReload.snapshot();
    }
}
