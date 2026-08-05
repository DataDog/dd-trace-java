package datadog.trace.api.openfeature;

import dev.openfeature.sdk.exceptions.FatalError;

public final class MissingBridgeChildMain {
  private MissingBridgeChildMain() {}

  public static void main(String[] args) {
    final Provider provider =
        new Provider(
            new Provider.Options().configurationSource("remote_config").environment("production"));
    try {
      provider.initialize(null);
      throw new AssertionError("Remote Configuration initialization unexpectedly succeeded");
    } catch (final FatalError error) {
      if (!error.getMessage().contains("version 1.65.0 or later")) {
        throw new AssertionError("Unexpected initialization error: " + error.getMessage(), error);
      }
      System.out.println("REMOTE_CONFIGURATION_ERROR=" + error.getMessage());
    } catch (final Exception error) {
      throw new AssertionError("Unexpected checked exception", error);
    }
  }
}
