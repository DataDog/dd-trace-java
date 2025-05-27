package datadog.telemetry;

import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessControlException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostInfo {
  private static final Path PROC_VERSION = FileSystems.getDefault().getPath("/proc/version");
  private static final Logger log = LoggerFactory.getLogger(TelemetryRequestBody.class);

  private static String hostname;
  private static String osName;
  private static String osVersion;
  private static String kernelRelease;
  private static String kernelVersion;
  private static String architecture;

  public static String getHostname() {
    if (hostname != null) {
      return hostname;
    }
    HostInfo.hostname = Config.get().getHostName();
    return hostname;
  }

  public static String getOsName() {
    if (osName == null) {
      if (OperatingSystem.isMacOs()) {
        // os.name == Mac OS X, while uanme -s == Darwin. We'll hardcode it to Darwin.
        osName = "Darwin";
      } else {
        osName = System.getProperty("os.name");
      }
    }
    return osName;
  }

  public static String getOsVersion() {
    if (osVersion == null) {
      osVersion = Os.getOsVersion();
    }
    return osVersion;
  }

  public static String getKernelName() {
    return getOsName();
  }

  public static String getKernelRelease() {
    if (kernelRelease == null) {
      // In Linux, os.version == uname -r
      kernelRelease = System.getProperty("os.version");
    }
    return kernelRelease;
  }

  public static String getKernelVersion() {
    if (kernelVersion == null) {
      // This is not really equivalent to uname -v in Linux, it has some additional info at the end,
      // like arch.
      String version = tryReadFile(PROC_VERSION);
      if (version != null) {
        version = version.trim();
        final int dashIdx = version.indexOf('#');
        if (dashIdx > 0) {
          version = version.substring(dashIdx);
        }
        kernelVersion = version;
      } else {
        kernelVersion = System.getProperty("os.version");
      }
    }
    return kernelVersion;
  }

  public static String getArchitecture() {
    if (architecture == null) {
      // In Linux, os.arch == uname -me
      architecture = System.getProperty("os.arch");
    }
    return architecture;
  }

  private static String tryReadFile(Path file) {
    String content = null;
    if (Files.isRegularFile(file)) {
      try {
        byte[] bytes = Files.readAllBytes(file);
        content = new String(bytes, StandardCharsets.ISO_8859_1);
      } catch (IOException | AccessControlException e) {
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
        } catch (IOException | AccessControlException e) {
          log.debug("Could not read {}", OS_RELEASE_PATH, e);
        }
        if (name != null && version != null) {
          return name + " " + version;
        }
      }
      return System.getProperty("os.version");
    }
  }
}
