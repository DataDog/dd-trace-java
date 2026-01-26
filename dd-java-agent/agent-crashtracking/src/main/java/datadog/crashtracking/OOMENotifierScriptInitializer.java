package datadog.crashtracking;

import static datadog.crashtracking.ConfigManager.writeConfigToPath;
import static datadog.crashtracking.Initializer.LOG;
import static datadog.crashtracking.Initializer.RWXRWXRWX;
import static datadog.crashtracking.Initializer.R_XR_XR_X;
import static datadog.crashtracking.Initializer.findAgentJar;
import static datadog.crashtracking.Initializer.getOomeNotifierTemplate;
import static datadog.crashtracking.Initializer.getScriptPathFromArg;
import static datadog.crashtracking.Initializer.pidFromSpecialFileName;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;
import static java.nio.file.attribute.PosixFilePermissions.fromString;

import datadog.environment.OperatingSystem;
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

public final class OOMENotifierScriptInitializer {
  private static final String OOME_NOTIFIER_SCRIPT_PREFIX = "dd_oome_notifier.";

  private OOMENotifierScriptInitializer() {}

  // @VisibleForTests
  static void initialize(String onOutOfMemoryVal) {
    if (onOutOfMemoryVal == null || onOutOfMemoryVal.isEmpty()) {
      LOG.debug(
          SEND_TELEMETRY,
          "'-XX:OnOutOfMemoryError' argument was not provided. OOME tracking is disabled.");
      return;
    }
    Path scriptPath = getOOMEScriptPath(onOutOfMemoryVal);
    if (scriptPath == null) {
      LOG.error(
          SEND_TELEMETRY,
          "OOME notifier script value ({}) does not follow the expected format: <path>/dd_oome_notifier.(sh|bat) %p. OOME tracking is disabled.",
          onOutOfMemoryVal);
      return;
    }
    String agentJar = findAgentJar();
    if (agentJar == null) {
      LOG.warn(
          SEND_TELEMETRY,
          "Unable to locate the agent jar. OOME notification will not work properly.");
      return;
    }
    if (!copyOOMEscript(scriptPath)) {
      return;
    }
    writeConfigToPath(scriptPath, "agent", agentJar);
  }

  private static Path getOOMEScriptPath(String onOutOfMemoryVal) {
    String path = getScriptPathFromArg(onOutOfMemoryVal, OOME_NOTIFIER_SCRIPT_PREFIX);
    return path == null ? null : Paths.get(path);
  }

  private static boolean copyOOMEscript(Path scriptPath) {
    Path scriptDirectory = scriptPath.getParent();

    // cleanup all stale process-specific generated files in the parent folder of the given OOME
    // notifier script
    ScriptCleanupVisitor.run(scriptDirectory);

    try {
      if (Files.exists(scriptDirectory)) {
        // can be safely ignored; if the folder exists we will just reuse it
        if (!Files.isWritable(scriptDirectory)) {
          LOG.warn(
              SEND_TELEMETRY,
              "Read only directory {}. OOME notification will not work properly.",
              scriptDirectory);
          return false;
        }
      } else {
        if (OperatingSystem.isWindows()) {
          Files.createDirectories(scriptDirectory);
        } else {
          Files.createDirectories(scriptDirectory, asFileAttribute(fromString(RWXRWXRWX)));
        }
      }
    } catch (UnsupportedOperationException e) {
      LOG.warn(
          SEND_TELEMETRY,
          "Unsupported permissions '"
              + RWXRWXRWX
              + "' for {}. OOME notification will not work properly.",
          scriptDirectory);
      return false;
    } catch (FileAlreadyExistsException ignored) {
      LOG.warn(SEND_TELEMETRY, "Path {} already exists and is not a directory.", scriptDirectory);
      return false;
    } catch (IOException e) {
      LOG.warn(
          SEND_TELEMETRY,
          "Failed to create writable OOME script folder {}. OOME notification will not work properly.",
          scriptDirectory);
      return false;
    }

    try {
      // do not overwrite existing
      if (!Files.exists(scriptPath)) {
        Files.copy(getOomeNotifierTemplate(), scriptPath);
      }
      if (!OperatingSystem.isWindows()) {
        Files.setPosixFilePermissions(scriptPath, fromString(R_XR_XR_X));
      }
    } catch (IOException e) {
      LOG.warn(
          SEND_TELEMETRY,
          "Failed to copy OOME script {}. OOME notification will not work properly.",
          scriptPath);
      return false;
    }
    return true;
  }

  private static class ScriptCleanupVisitor implements FileVisitor<Path> {
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
      String pid = pidFromSpecialFileName(fileName);
      if (pid != null && !pid.equals(PidHelper.getPid())) {
        if (this.pidSet == null) {
          // if pidSet is not initialized, initialize it
          // this will fork jps to get the list of Java PIDs
          this.pidSet = PidHelper.getJavaPids();
        }
        if (!this.pidSet.contains(pid)) {
          LOG.debug("Cleaning process specific file {}", file);
          Files.delete(file);
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
