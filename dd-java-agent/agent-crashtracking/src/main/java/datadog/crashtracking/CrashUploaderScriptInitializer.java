package datadog.crashtracking;

import static datadog.crashtracking.ConfigManager.writeConfigToPath;
import static datadog.crashtracking.Initializer.LOG;
import static datadog.crashtracking.Initializer.RWXRWXRWX;
import static datadog.crashtracking.Initializer.R_XR_XR_X;
import static datadog.crashtracking.Initializer.findAgentJar;
import static datadog.crashtracking.Initializer.getCrashUploaderTemplate;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;
import static java.nio.file.attribute.PosixFilePermissions.fromString;
import static java.util.Locale.ROOT;

import datadog.environment.OperatingSystem;
import datadog.environment.SystemProperties;
import datadog.trace.util.PidHelper;
import datadog.trace.util.Strings;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CrashUploaderScriptInitializer {
  private static final String SETUP_FAILURE_MESSAGE = "Crash tracking will not work properly.";

  private CrashUploaderScriptInitializer() {}

  // @VisibleForTests
  static void initialize(String onErrorVal, String onErrorFile) {
    if (onErrorVal == null || onErrorVal.isEmpty()) {
      LOG.debug(
          SEND_TELEMETRY, "'-XX:OnError' argument was not provided. Crash tracking is disabled.");
      return;
    }
    if (onErrorFile == null || onErrorFile.isEmpty()) {
      onErrorFile = SystemProperties.get("user.dir") + "/hs_err_pid" + PidHelper.getPid() + ".log";
      LOG.debug("No -XX:ErrorFile value, defaulting to {}", onErrorFile);
    } else {
      onErrorFile = Strings.replace(onErrorFile, "%p", PidHelper.getPid());
    }

    String agentJar = findAgentJar();
    if (agentJar == null) {
      LOG.warn(SEND_TELEMETRY, "Unable to locate the agent jar. " + SETUP_FAILURE_MESSAGE);
      return;
    }

    Path scriptPath = Paths.get(onErrorVal.replace(" %p", ""));
    boolean isDDCrashUploader =
        scriptPath.getFileName().toString().toLowerCase(ROOT).contains("dd_crash_uploader");
    if (isDDCrashUploader && !copyCrashUploaderScript(scriptPath, onErrorFile, agentJar)) {
      return;
    }

    writeConfigToPath(scriptPath, "agent", agentJar, "hs_err", onErrorFile);
  }

  private static boolean copyCrashUploaderScript(
      Path scriptPath, String onErrorFile, String agentJar) {
    Path scriptDirectory = scriptPath.getParent();
    try {
      if (OperatingSystem.isWindows()) {
        Files.createDirectories(scriptDirectory);
      } else {
        Files.createDirectories(scriptDirectory, asFileAttribute(fromString(RWXRWXRWX)));
      }
    } catch (UnsupportedOperationException e) {
      LOG.warn(
          SEND_TELEMETRY,
          "Unsupported permissions '" + RWXRWXRWX + "' for {}. " + SETUP_FAILURE_MESSAGE,
          scriptDirectory);
      return false;
    } catch (FileAlreadyExistsException ignored) {
      // can be safely ignored; if the folder exists we will just reuse it
      if (!Files.isWritable(scriptDirectory)) {
        LOG.warn(
            SEND_TELEMETRY, "Read only directory {}. " + SETUP_FAILURE_MESSAGE, scriptDirectory);
        return false;
      }
    } catch (IOException e) {
      LOG.warn(
          SEND_TELEMETRY,
          "Failed to create writable crash tracking script folder {}. " + SETUP_FAILURE_MESSAGE,
          scriptDirectory);
      return false;
    }
    try {
      LOG.debug("Writing crash uploader script: {}", scriptPath);
      writeCrashUploaderScript(getCrashUploaderTemplate(), scriptPath, agentJar, onErrorFile);
    } catch (IOException e) {
      LOG.warn(
          SEND_TELEMETRY,
          "Failed to copy crash tracking script {}. " + SETUP_FAILURE_MESSAGE,
          scriptPath);
      return false;
    }
    return true;
  }

  private static void writeCrashUploaderScript(
      InputStream template, Path scriptPath, String execClass, String crashFile)
      throws IOException {
    if (!Files.exists(scriptPath)) {
      try (BufferedReader br = new BufferedReader(new InputStreamReader(template));
          BufferedWriter bw = Files.newBufferedWriter(scriptPath)) {
        String line;
        while ((line = br.readLine()) != null) {
          bw.write(template(line, execClass, crashFile));
          bw.newLine();
        }
      }
      if (!OperatingSystem.isWindows()) {
        Files.setPosixFilePermissions(scriptPath, fromString(R_XR_XR_X));
      }
    }
  }

  private static String template(String line, String execClass, String crashFile) {
    line = Strings.replace(line, "!AGENT_JAR!", execClass);
    line = Strings.replace(line, "!JAVA_HOME!", SystemProperties.get("java.home"));
    if (crashFile != null) {
      line = Strings.replace(line, "!JAVA_ERROR_FILE!", crashFile);
    }
    return line;
  }
}
