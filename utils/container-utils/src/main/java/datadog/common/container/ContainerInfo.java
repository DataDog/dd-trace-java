package datadog.common.container;

import datadog.trace.config.inversion.ConfigHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses container information from /proc/self/cgroup. Implementation based largely on
 * Qard/container-info
 */
@SuppressForbidden
@SuppressFBWarnings(
    value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
    justification = "DEFAULT_CGROUP_MOUNT_PATH")
public class ContainerInfo {

  private static final Logger log = LoggerFactory.getLogger(ContainerInfo.class);

  private static final Path CGROUP_DEFAULT_PROCFILE = Paths.get("/proc/self/cgroup");
  private static final Path DEFAULT_CGROUP_MOUNT_PATH = Paths.get("/sys/fs/cgroup");
  private static final Path HOST_GROUP_NAMESPACE = Paths.get("/proc/self/ns/cgroup");

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

  private static final long PROC_CGROUP_INIT_INO = 0xEFFFFFFBL;
  private static final String CGROUPV1_BASE_CONTROLLER = "memory";
  private static final String CGROUPV2_BASE_CONTROLLER = "";

  /**
   * Environment variable injected by the Datadog Cluster Agent admission controller into
   * application containers. It is not meant to be set by users.
   */
  private static final String EXTERNAL_ENV_VARIABLE = "DD_EXTERNAL_ENV";

  private static final String ENTITY_ID;
  private static final String EXTERNAL_ENV;

  @SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
  public String containerId;

  @SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
  public String containerTagsHash;

  @SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
  public String podId;

  @SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
  public List<CGroupInfo> cGroups = new ArrayList<>();

  public String getContainerId() {
    return containerId;
  }

  public void setContainerId(String containerId) {
    this.containerId = containerId;
  }

  public String getContainerTagsHash() {
    return containerTagsHash;
  }

  public void setContainerTagsHash(String containerTagsHash) {
    this.containerTagsHash = containerTagsHash;
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

  /**
   * Returns the entity id to send as the {@code Datadog-Entity-ID} header: the container id when
   * known, otherwise the cgroup inode, or {@code null} if neither is available.
   */
  public @Nullable String getEntityId() {
    return ENTITY_ID;
  }

  /**
   * Returns the value of the {@code DD_EXTERNAL_ENV} environment variable injected by the Datadog
   * Cluster Agent admission controller, to be sent as the {@code Datadog-External-Env} header, or
   * {@code null} if it is unset, empty or not a valid HTTP header value.
   */
  public @Nullable String getExternalEnv() {
    return EXTERNAL_ENV;
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

    ENTITY_ID = readEntityID(containerInfo, isHostCgroupNamespace(), DEFAULT_CGROUP_MOUNT_PATH);
    EXTERNAL_ENV = sanitizeExternalEnv(ConfigHelper.env(EXTERNAL_ENV_VARIABLE));
  }

  static @Nullable String readEntityID(
      ContainerInfo containerInfo, boolean isHostCgroupNamespace, Path cgroupMountPath) {
    String cid = containerInfo.getContainerId();
    if (cid != null && !cid.isEmpty()) {
      return "cid-" + cid;
    }
    if (isHostCgroupNamespace) {
      return null;
    }
    return getCgroupInode(cgroupMountPath, containerInfo.getCGroups());
  }

  /**
   * Returns the external environment value to forward to the Datadog Agent, or {@code null} if it
   * should not be sent. The value is forwarded unparsed, after trimming surrounding whitespace and
   * control characters; it is discarded when empty or when it contains characters that are not
   * allowed in an HTTP header value.
   */
  static @Nullable String sanitizeExternalEnv(@Nullable String externalEnv) {
    if (externalEnv == null) {
      return null;
    }

    String value = externalEnv.trim();
    if (value.isEmpty()) {
      return null;
    }

    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if ((c < 0x20 && c != '\t') || c >= 0x7f) {
        // the admission controller sets this value, so a malformed one silently disables this
        // origin detection fallback
        log.warn(
            "Ignoring {} because it contains characters not allowed in an HTTP header value",
            EXTERNAL_ENV_VARIABLE);
        return null;
      }
    }

    return value;
  }

  /** Checks if the agent is running in the host cgroup namespace. */
  static boolean isHostCgroupNamespace() {
    // Currently, host namespace inode number is hardcoded, which can be used to detect if it's
    // running in host namespace or not (does not work when running in DinD)
    // https://github.com/torvalds/linux/blob/5859a2b1991101d6b978f3feb5325dad39421f29/include/linux/proc_ns.h#L41-L49
    return readInode(HOST_GROUP_NAMESPACE) == PROC_CGROUP_INIT_INO;
  }

  /**
   * Returns the inode of the cgroup controller if it exists, otherwise an empty string. The inode
   * is prefixed with "in-" and is used by the agent to retrieve the container ID.
   */
  static @Nullable String getCgroupInode(Path cgroupMountPath, List<CGroupInfo> cgroups) {
    // It first tries to get the cGroupV1 "memory" controller inode, and if that fails, it tries to
    // get the cGroupV2 inode.
    for (String controller : Arrays.asList(CGROUPV1_BASE_CONTROLLER, CGROUPV2_BASE_CONTROLLER)) {
      for (CGroupInfo cgroup : cgroups) {
        if (cgroup.getControllers().contains(controller)) {
          Path path = Paths.get(cgroupMountPath.toString(), controller, cgroup.getPath());
          long inode = readInode(path);
          // ignore invalid and root inode
          if (inode > 2) {
            return "in-" + inode;
          }
        }
      }
    }
    return null;
  }

  /**
   * @return 0 - if it couldn't read inode
   */
  static long readInode(Path path) {
    try {
      return (long) Files.getAttribute(path, "unix:ino");
    } catch (Exception e) {
      log.debug("Unable to read cgroup inode of {} because of {}", path, e.getClass());
    }
    return 0;
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

      replaceContainerIdIfBetter(containerInfo, cGroupInfo);
    }

    return containerInfo;
  }

  /**
   * Keeps the best available container id candidate across cgroup lines.
   *
   * <p>Some environments expose both detailed cgroup-v1 controller paths and a cgroup-v2 membership
   * path (0::...) where the tail can be task-level and less specific.
   */
  private static void replaceContainerIdIfBetter(
      ContainerInfo currentContainerInfo, CGroupInfo candidate) {
    String candidateContainerId = candidate.getContainerId();
    if (candidateContainerId == null) return;

    String currentContainerId = currentContainerInfo.getContainerId();
    if (currentContainerId != null
        && currentContainerId.matches(CONTAINER_REGEX)
        && candidateContainerId.matches(TASK_REGEX)
        && currentContainerInfo.getPodId() != null
        && candidate.getPodId() == null) {
      // Do not replace a pod-scoped container id with a task-scoped id from a less specific path.
      // This protects hybrid cgroup output where trailing 0:: lines contain only task ids.
      return;
    }

    currentContainerInfo.setContainerId(candidateContainerId);
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
