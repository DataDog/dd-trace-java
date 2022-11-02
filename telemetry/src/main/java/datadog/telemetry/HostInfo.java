package datadog.telemetry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostInfo {

  private static final Path PROC_HOSTNAME =
      FileSystems.getDefault().getPath("/proc/sys/kernel/hostname");
  private static final Path ETC_HOSTNAME = FileSystems.getDefault().getPath("/etc/hostname");

  private static final Path PROC_VERSION = FileSystems.getDefault().getPath("/proc/version");
  private static final List<Path> HOSTNAME_FILES = Arrays.asList(PROC_HOSTNAME, ETC_HOSTNAME);

  private static final Logger log = LoggerFactory.getLogger(RequestBuilder.class);

  public static String getHostname() {
    String hostname = null;
    for (Path file : HOSTNAME_FILES) {
      hostname = tryReadFile(file);
      if (null != hostname) {
        break;
      }
    }

    if (hostname == null) {
      try {
        hostname = getHostNameFromLocalHost();
      } catch (UnknownHostException e) {
        // purposefully left empty
      }
    }

    if (hostname != null) {
      hostname = hostname.trim();
    } else {
      log.warn("Could not determine hostname");
      hostname = "";
    }

    return hostname;
  }

  public static String getOsName() {
    return System.getProperty("os.name");
  }

  public static String getOsVersion() {
    return Os.getOsVersion();
  }

  public static String getKernelName() {
    // In Linux, os.name == uname -s
    return System.getProperty("os.name");
  }

  public static String getKernelRelease() {
    // In Linux, os.version == uname -r
    return System.getProperty("os.version");
  }

  public static String getKernelVersion() {
    // This is not really equivalent to uname -v in Linux, it has some additional info at the end,
    // like arch.
    String version = tryReadFile(PROC_VERSION);
    if (version != null) {
      version = version.trim();
      final int dashIdx = version.indexOf('#');
      if (dashIdx > 0) {
        version = version.substring(dashIdx);
      }
      return version;
    }
    return System.getProperty("os.version");
  }

  private static String getHostNameFromLocalHost() throws UnknownHostException {
    return InetAddress.getLocalHost().getHostName();
  }

  private static String tryReadFile(Path file) {
    String content = null;
    if (Files.isRegularFile(file)) {
      try {
        byte[] bytes = Files.readAllBytes(file);
        content = new String(bytes, StandardCharsets.ISO_8859_1);
      } catch (IOException e) {
        log.debug("Could not read {}", file, e);
      }
    }
    return content;
  }

  private static class Os {
    private static final Pattern OS_RELEASE_PATTERN =
        Pattern.compile("(?<name>[A-Z]+)=\"(?<value>[^\"]+)\"");
    private static final Path OS_RELEASE_PATH = Paths.get("/etc/os-release");

    public static String getOsVersion() {
      if (Files.isRegularFile(OS_RELEASE_PATH)) {
        String name = null;
        String version = null;
        try {
          List<String> lines = Files.readAllLines(OS_RELEASE_PATH, StandardCharsets.ISO_8859_1);
          for (String l : lines) {
            Matcher matcher = OS_RELEASE_PATTERN.matcher(l);
            if (!matcher.matches()) {
              continue;
            }
            String nameGroup = matcher.group("name");
            if ("NAME".equals(nameGroup)) {
              name = matcher.group("value");
            } else if ("VERSION".equals(nameGroup)) {
              version = matcher.group("value");
            }
          }
        } catch (IOException e) {
        }
        if (name != null && version != null) {
          return name + " " + version;
        }
      }
      return System.getProperty("os.version");
    }
  }
}
