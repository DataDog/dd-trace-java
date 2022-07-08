package datadog.common.version;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
        final StringBuilder sb = new StringBuilder();
        final char[] buffer = new char[1 << 8];
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
          sb.append(buffer, 0, read);
        }
        version = sb.toString().trim();
      } else {
        log.error("No version file found");
      }
    } catch (final Exception e) {
      log.error("Cannot read version file", e);
    }
    VERSION = version;
  }
}
