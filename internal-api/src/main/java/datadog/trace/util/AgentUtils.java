package datadog.trace.util;

import datadog.trace.api.Config;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

public abstract class AgentUtils {

  private AgentUtils() {}

  public static File getAgentJar() {
    String agentJarUriString = Config.get().getCiVisibilityAgentJarUri();
    if (agentJarUriString == null || agentJarUriString.isEmpty()) {
      throw new IllegalArgumentException("Agent JAR URI is not set in config");
    }

    try {
      URI agentJarUri = new URI(agentJarUriString);
      return new File(agentJarUri);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Malformed agent JAR URI: " + agentJarUriString, e);
    }
  }
}
