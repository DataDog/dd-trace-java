package datadog.trace.bootstrap.config.provider; 

public class FfiStableConfig {
    private static native long new_configurator(boolean debug_logs);
    private static native long get_configuration(long configurator);

    static {
        // This actually loads the shared object that we'll be creating.
        // The actual location of the .so or .dll may differ based on your
        // platform.
        System.loadLibrary("datadog_library_config_java");
    }

}
