package datadog.trace.api;

/**
 * Helpers for building local Datadog Agent URLs.
 *
 * <p>Kept out of {@link Config} so that tests mocking {@code Config}'s static methods don't stub
 * these helpers as well.
 */
public final class AgentUrls {

  private AgentUrls() {}

  /**
   * Returns {@code agentUrl}, or a placeholder HTTP URL built from {@code agentHost} and {@code
   * agentPort} when it is a unix domain socket URL, as requests are tunnelled over the socket.
   */
  public static String toHttpUrl(
      final String agentUrl, final String agentHost, final int agentPort) {
    if (agentUrl.startsWith("unix:")) {
      // provide placeholder agent URL, in practice we'll be tunnelling over UDS
      return "http://" + agentHost + ":" + agentPort;
    }
    return agentUrl;
  }
}
