package datadog.crashtracking;

import static datadog.crashtracking.Initializer.LOG;
import static datadog.crashtracking.Initializer.PID_PREFIX;
import static datadog.crashtracking.Initializer.RWXRWXRWX;
import static datadog.crashtracking.Initializer.R_XR_XR_X;
import static datadog.crashtracking.Initializer.findAgentJar;
import static datadog.crashtracking.Initializer.getOomeNotifierTemplate;
import static datadog.crashtracking.Initializer.writeConfig;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;
import static java.nio.file.attribute.PosixFilePermissions.fromString;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

import datadog.trace.api.Config;
import datadog.trace.util.PidHelper;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class OOMENotifierScriptInitializer {
  private static final Pattern OOME_NOTIFIER_SCRIPT_PATTERN =
      Pattern.compile("(.*?dd_oome_notifier[.](sh|bat))\\s+(%p)", CASE_INSENSITIVE);

  private OOMENotifierScriptInitializer() {}

  // @VisibleForTests
  static void initialize(String onOutOfMemoryVal) {
    if (onOutOfMemoryVal == null || onOutOfMemoryVal.isEmpty()) {
      LOG.debug("'-XX:OnOutOfMemoryError' argument was not provided. OOME tracking is disabled.");
      return;
    }
    Path scriptPath = getOOMEScripPath(onOutOfMemoryVal);
    System.out.println("===> OOME notifier script path: " + scriptPath);
    if (scriptPath == null) {
      LOG.debug(
          "OOME notifier script value ({}) does not follow the expected format: <path>/dd_oome_notifier.(sh|bat) %p. OOME tracking is disabled.",
          onOutOfMemoryVal);
      return;
    }
    String agentJar = findAgentJar();
    if (agentJar == null) {
      LOG.warn("Unable to locate the agent jar. OOME notification will not work properly.");
      return;
    }
    if (!copyOOMEscript(scriptPath)) {
      return;
    }
    String tags = getTags();
    writeConfig(scriptPath, "agent", agentJar, "tags", tags);
  }

  private static String getTags() {
    return Config.get().getMergedJmxTags().entrySet().stream()
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.joining(","));
  }

  private static Path getOOMEScripPath(String onOutOfMemoryVal) {
    Matcher m = OOME_NOTIFIER_SCRIPT_PATTERN.matcher(onOutOfMemoryVal);
    if (!m.find()) {
      return null;
    }
    return Paths.get(m.group(1));
  }

  private static boolean copyOOMEscript(Path scriptPath) {
    Path scriptDirectory = scriptPath.getParent();

    // cleanup all stale process-specific generated files in the parent folder of the given OOME
    // notifier script
    ScriptCleanupVisitor.run(scriptDirectory);

    try {
      Files.createDirectories(scriptDirectory, asFileAttribute(fromString(RWXRWXRWX)));
    } catch (UnsupportedOperationException e) {
      LOG.warn(
          "Unsupported permissions {} for {}. OOME notification will not work properly.",
          RWXRWXRWX,
          scriptDirectory);
      return false;
    } catch (FileAlreadyExistsException ignored) {
      // can be safely ignored; if the folder exists we will just reuse it
      if (!Files.isWritable(scriptDirectory)) {
        LOG.warn(
            "Read only directory {}. OOME notification will not work properly.", scriptDirectory);
        return false;
      }
    } catch (IOException e) {
      LOG.warn(
          "Failed to create writable OOME script folder {}. OOME notification will not work properly.",
          scriptDirectory);
      return false;
    }

    try {
      Files.copy(getOomeNotifierTemplate(), scriptPath, REPLACE_EXISTING);
      Files.setPosixFilePermissions(scriptPath, fromString(R_XR_XR_X));
    } catch (IOException e) {
      LOG.warn(
          "Failed to copy OOME script {}. OOME notification will not work properly.", scriptPath);
      return false;
    }
    return true;
  }

  private static class ScriptCleanupVisitor implements FileVisitor<Path> {
    private static final Pattern PID_PATTERN = Pattern.compile(".*?" + PID_PREFIX + "(\\d+)");

    private Set<String> pidSet;

    static void run(Path dir) {
      try {
        if (Files.exists(dir)) {
          Files.walkFileTree(dir, new ScriptCleanupVisitor());
        }
      } catch (IOException e) {
        if (LOG.isDebugEnabled()) {
          LOG.info("Failed cleaning up process specific files in {}", dir, e);
        } else {
          LOG.info("Failed cleaning up process specific files in {}: {}", dir, e.toString());
        }
      }
    }

    private ScriptCleanupVisitor() {}

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      return CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      String fileName = file.getFileName().toString();
      Matcher matcher = PID_PATTERN.matcher(fileName);
      if (matcher.find()) {
        String pid = matcher.group(1);
        if (pid != null && !pid.equals(PidHelper.getPid())) {
          if (this.pidSet == null) {
            this.pidSet = PidHelper.getJavaPids(); // only fork jps when required
          }
          if (!this.pidSet.contains(pid)) {
            LOG.debug("Cleaning process specific file {}", file);
            Files.delete(file);
          }
        }
      }
      return CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      LOG.debug("Failed to delete file {}", file, exc);
      return CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      return CONTINUE;
    }
  }
}
