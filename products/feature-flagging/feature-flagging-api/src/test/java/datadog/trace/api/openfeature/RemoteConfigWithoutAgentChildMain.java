package datadog.trace.api.openfeature;

import dev.openfeature.sdk.exceptions.FatalError;

public final class RemoteConfigWithoutAgentChildMain {
  private RemoteConfigWithoutAgentChildMain() {}

  public static void main(String[] args) {
    System.setProperty("dd.feature.flags.configuration.source", "remote_config");
    final Provider provider = new Provider();
    try {
      provider.initialize(null);
      throw new AssertionError("Remote Configuration initialization unexpectedly succeeded");
    } catch (final FatalError error) {
      if (!error.getMessage().contains("requires dd-java-agent.jar")) {
        throw new AssertionError("Unexpected initialization error: " + error.getMessage(), error);
      }
      System.out.println("REMOTE_CONFIGURATION_ERROR=" + error.getMessage());
    } catch (final Exception error) {
      throw new AssertionError("Unexpected checked exception", error);
    }
  }
}
