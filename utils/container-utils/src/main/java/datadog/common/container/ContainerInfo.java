package datadog.common.container;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses container information from /proc/self/cgroup. Implementation based largely on
 * Qard/container-info
 */
@SuppressForbidden
public class ContainerInfo {

  private static final Logger log = LoggerFactory.getLogger(ContainerInfo.class);

  private static final Path CGROUP_DEFAULT_PROCFILE = Paths.get("/proc/self/cgroup");
  // The second part is the PCF/Garden regexp. We assume no suffix ($) to avoid matching pod UIDs
  // See https://github.com/DataDog/datadog-agent/blob/7.40.x/pkg/util/cgroups/reader.go#L50
  private static final String UUID_REGEX =
      "[0-9a-f]{8}[-_][0-9a-f]{4}[-_][0-9a-f]{4}[-_][0-9a-f]{4}[-_][0-9a-f]{12}|[0-9a-f]{8}(?:-[0-9a-f]{4}){4}$";
  private static final String CONTAINER_REGEX = "[0-9a-f]{64}";
  private static final String TASK_REGEX = "[0-9a-f]{32}-\\d+";
  private static final Pattern LINE_PATTERN = Pattern.compile("(\\d+):([^:]*):(.+)$");
  private static final Pattern POD_PATTERN =
      Pattern.compile("(?:.+)?pod(" + UUID_REGEX + ")(?:.slice)?$");
  private static final Pattern CONTAINER_PATTERN =
      Pattern.compile(
          "(?:.+)?(" + UUID_REGEX + "|" + CONTAINER_REGEX + "|" + TASK_REGEX + ")(?:.scope)?$");

  private static final ContainerInfo INSTANCE;

  public String containerId;
  public String podId;
  public List<CGroupInfo> cGroups = new ArrayList<>();

  public String getContainerId() {
    return containerId;
  }

  public void setContainerId(String containerId) {
    this.containerId = containerId;
  }

  public String getPodId() {
    return podId;
  }

  public void setPodId(String podId) {
    this.podId = podId;
  }

  public List<CGroupInfo> getCGroups() {
    return cGroups;
  }

  public void setcGroups(List<CGroupInfo> cGroups) {
    this.cGroups = cGroups;
  }

  static {
    ContainerInfo containerInfo = new ContainerInfo();
    if (ContainerInfo.isRunningInContainer()) {
      try {
        containerInfo = ContainerInfo.fromDefaultProcFile();
      } catch (final IOException | ParseException e) {
        log.error("Unable to parse proc file");
      }
    }

    INSTANCE = containerInfo;
  }

  public static class CGroupInfo {
    public int id;
    public String path;
    public List<String> controllers;
    public String containerId;
    public String podId;

    public int getId() {
      return id;
    }

    public void setId(int id) {
      this.id = id;
    }

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public List<String> getControllers() {
      return controllers;
    }

    public void setControllers(List<String> controllers) {
      this.controllers = controllers;
    }

    public String getContainerId() {
      return containerId;
    }

    public void setContainerId(String containerId) {
      this.containerId = containerId;
    }

    public String getPodId() {
      return podId;
    }

    public void setPodId(String podId) {
      this.podId = podId;
    }
  }

  public static ContainerInfo get() {
    return INSTANCE;
  }

  public static boolean isRunningInContainer() {
    return Files.isReadable(CGROUP_DEFAULT_PROCFILE);
  }

  public static ContainerInfo fromDefaultProcFile() throws IOException, ParseException {
    return fromProcFile(CGROUP_DEFAULT_PROCFILE);
  }

  static ContainerInfo fromProcFile(Path path) throws IOException, ParseException {
    final String content = new String(Files.readAllBytes(path));
    if (content.isEmpty()) {
      log.debug("Proc file is empty");
      return new ContainerInfo();
    }
    return parse(content);
  }

  public static ContainerInfo parse(final String cgroupsContent) throws ParseException {
    final ContainerInfo containerInfo = new ContainerInfo();

    final String[] lines = cgroupsContent.split("\n");
    for (final String line : lines) {
      final CGroupInfo cGroupInfo = parseLine(line);

      containerInfo.getCGroups().add(cGroupInfo);

      if (cGroupInfo.getPodId() != null) {
        containerInfo.setPodId(cGroupInfo.getPodId());
      }

      if (cGroupInfo.getContainerId() != null) {
        containerInfo.setContainerId(cGroupInfo.getContainerId());
      }
    }

    return containerInfo;
  }

  static CGroupInfo parseLine(final String line) throws ParseException {
    final Matcher matcher = LINE_PATTERN.matcher(line);

    if (!matcher.matches()) {
      throw new ParseException("Unable to match cgroup", 0);
    }

    final CGroupInfo cGroupInfo = new CGroupInfo();
    cGroupInfo.setId(Integer.parseInt(matcher.group(1)));
    cGroupInfo.setControllers(Arrays.asList(matcher.group(2).split(",")));

    final String path = matcher.group(3);
    final String[] pathParts = path.split("/");

    cGroupInfo.setPath(path);

    if (pathParts.length >= 1) {
      final Matcher containerIdMatcher = CONTAINER_PATTERN.matcher(pathParts[pathParts.length - 1]);
      final String containerId = containerIdMatcher.matches() ? containerIdMatcher.group(1) : null;
      cGroupInfo.setContainerId(containerId);
    }

    if (pathParts.length >= 2) {
      final Matcher podIdMatcher = POD_PATTERN.matcher(pathParts[pathParts.length - 2]);
      final String podId = podIdMatcher.matches() ? podIdMatcher.group(1) : null;
      cGroupInfo.setPodId(podId);
    }

    return cGroupInfo;
  }
}
