package datadog.common.socket;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_SOCKET_PATH;

import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.config.TracerConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SocketUtils {
  private static final Logger log = LoggerFactory.getLogger(SocketUtils.class);

  @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
  public static String discoverApmSocket(final Config config) {
    String unixDomainSocket = config.getAgentUnixDomainSocket();
    if (!Platform.isWindows()) {
      if (unixDomainSocket == null
          && Config.get().isAgentConfiguredUsingDefault()
          && new File(DEFAULT_TRACE_AGENT_SOCKET_PATH).exists()) {
        log.info("Detected {}.  Using it to send trace data.", DEFAULT_TRACE_AGENT_SOCKET_PATH);
        unixDomainSocket = DEFAULT_TRACE_AGENT_SOCKET_PATH;
      }
    } else /* windows */ {
      if (unixDomainSocket != null) {
        log.warn(
            "{} setting not supported on {}.  Reverting to the default.",
            TracerConfig.AGENT_UNIX_DOMAIN_SOCKET,
            System.getProperty("os.name"));
        unixDomainSocket = null;
      }
    }
    return unixDomainSocket;
  }
}
