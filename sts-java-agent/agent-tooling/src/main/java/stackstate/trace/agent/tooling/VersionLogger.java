package stackstate.trace.agent.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VersionLogger {

  /** Log version strings for dd-trace-ot, dd-trace-pai, and sts-java-agent */
  public static void logAllVersions() {
    log.info(
        "dd-trace-ot - version: {}",
        getVersionString(Utils.getAgentClassLoader().getResourceAsStream("dd-trace-ot.version")));
    log.info(
        "dd-trace-api - version: {}",
        getVersionString(Utils.getAgentClassLoader().getResourceAsStream("dd-trace-api.version")));
    log.info(
        "sts-java-agent - version: {}",
        getVersionString(
            ClassLoader.getSystemClassLoader().getResourceAsStream("dd-java-agent.version")));
  }

  private static String getVersionString(InputStream stream) {
    String v;
    try {
      final StringBuilder sb = new StringBuilder();
      final BufferedReader br = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
      for (int c = br.read(); c != -1; c = br.read()) sb.append((char) c);

      v = sb.toString().trim();
    } catch (final Exception e) {
      log.error("failed to read version stream", e);
      v = "unknown";
    } finally {
      try {
        if (null != stream) {
          stream.close();
        }
      } catch (IOException e) {
      }
    }
    return v;
  }
}
