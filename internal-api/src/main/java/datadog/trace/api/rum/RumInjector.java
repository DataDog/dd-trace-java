package datadog.trace.api.rum;

public final class RumInjector {

  private static volatile String snippet;

  private static volatile RumConfig config;

  public static boolean isEnabled() {
    // Check lazy config init
    // TODO Config.isRumEnabled()? + valid config
    return false;
  }

  public static String getSnippet() {
    return null;
  }
}
