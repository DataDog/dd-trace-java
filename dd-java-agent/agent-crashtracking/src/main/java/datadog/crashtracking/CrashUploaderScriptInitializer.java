package datadog.crashtracking;

import static datadog.crashtracking.ConfigManager.writeConfigToPath;
import static datadog.crashtracking.Initializer.LOG;
import static datadog.crashtracking.Initializer.findAgentJar;
import static datadog.crashtracking.Initializer.getCrashUploaderTemplate;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.util.Locale.ROOT;

import datadog.environment.SystemProperties;
import datadog.trace.util.PidHelper;
import datadog.trace.util.Strings;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class CrashUploaderScriptInitializer {
  private static final String SETUP_FAILURE_MESSAGE = "Crash tracking will not work properly.";

  private CrashUploaderScriptInitializer() {}

  // @VisibleForTests
  static void initialize(String onErrorVal, String onErrorFile) {
    initialize(onErrorVal, onErrorFile, null);
  }

  // @VisibleForTests
  static void initialize(String onErrorVal, String onErrorFile, String javacorePath) {
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

    File scriptFile = new File(onErrorVal.replace(" %p", ""));
    boolean isDDCrashUploader =
        scriptFile.getName().toLowerCase(ROOT).contains("dd_crash_uploader");
    if (isDDCrashUploader && !copyCrashUploaderScript(scriptFile, onErrorFile, agentJar)) {
      return;
    }

    if (javacorePath != null && !javacorePath.isEmpty()) {
      writeConfigToPath(scriptFile, "agent", agentJar, "javacore_path", javacorePath);
    } else {
      writeConfigToPath(scriptFile, "agent", agentJar, "hs_err", onErrorFile);
    }
  }

  private static boolean copyCrashUploaderScript(
      File scriptFile, String onErrorFile, String agentJar) {
    File scriptDirectory = scriptFile.getParentFile();
    if (!scriptDirectory.exists()) {
      if (!scriptDirectory.mkdirs()) {
        LOG.warn(
            SEND_TELEMETRY,
            "Failed to create writable crash tracking script folder {}. " + SETUP_FAILURE_MESSAGE,
            scriptDirectory);
        return false;
      }
      scriptDirectory.setReadable(true, false);
      scriptDirectory.setWritable(true, false);
      if (!scriptDirectory.setReadable(true, false)
          || !scriptDirectory.setWritable(true, false)
          || !scriptDirectory.setExecutable(true, false)) {
        LOG.warn(
            SEND_TELEMETRY,
            "Failed to set permissions on crash tracking script folder {}. {}",
            scriptDirectory, SETUP_FAILURE_MESSAGE);
      }
    }
    if (!scriptDirectory.canWrite()) {
      LOG.warn(SEND_TELEMETRY, "Read only directory {}. " + SETUP_FAILURE_MESSAGE, scriptDirectory);
      return false;
    }
    try {
      LOG.debug("Writing crash uploader script: {}", scriptFile);
      writeCrashUploaderScript(getCrashUploaderTemplate(), scriptFile, agentJar, onErrorFile);
    } catch (IOException e) {
      LOG.warn(
          SEND_TELEMETRY,
          "Failed to copy crash tracking script {}. " + SETUP_FAILURE_MESSAGE,
          scriptFile);
      return false;
    }
    return true;
  }

  private static void writeCrashUploaderScript(
      InputStream template, File scriptFile, String execClass, String crashFile)
      throws IOException {
    if (!scriptFile.exists()) {
      try (BufferedReader br = new BufferedReader(new InputStreamReader(template));
          BufferedWriter bw =
              new BufferedWriter(
                  new OutputStreamWriter(
                      new FileOutputStream(scriptFile), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          bw.write(template(line, execClass, crashFile));
          bw.newLine();
        }
      }
      scriptFile.setReadable(true, false);
      scriptFile.setWritable(false, false);
      scriptFile.setExecutable(true, false);
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
