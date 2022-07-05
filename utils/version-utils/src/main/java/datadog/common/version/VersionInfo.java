package datadog.common.version;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressFBWarnings("OS_OPEN_STREAM")
public class VersionInfo {

  private static final Logger log = LoggerFactory.getLogger(VersionInfo.class);

  public static final String LIBRARY_VERSION_TAG = "library_version";
  public static final String PROFILER_VERSION_TAG = "profiler_version";
  public static final String VERSION;

  static {
    String version = "unknown";
    try {
      final InputStream is =
          VersionInfo.class.getClassLoader().getResourceAsStream("version-utils.version");
      if (is != null) {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        version = reader.lines().collect(Collectors.joining(System.lineSeparator())).trim();
      } else {
        log.error("No version file found");
      }
    } catch (final Exception e) {
      log.error("Cannot read version file", e);
    }
    VERSION = version;
  }
}
