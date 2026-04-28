package datadog.crashtracking;

import static datadog.crashtracking.ConfigManager.writeConfigToPath;
import static datadog.crashtracking.Initializer.LOG;
import static datadog.crashtracking.Initializer.findAgentJar;
import static datadog.crashtracking.Initializer.getOomeNotifierTemplate;
import static datadog.crashtracking.Initializer.getScriptPathFromArg;
import static datadog.crashtracking.Initializer.pidFromSpecialFileName;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.trace.util.PidHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    File scriptFile = getOOMEScriptFile(onOutOfMemoryVal);
    if (scriptFile == null) {
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
    if (!copyOOMEscript(scriptFile)) {
      return;
    }
    writeConfigToPath(scriptFile, "agent", agentJar);
  }

  private static File getOOMEScriptFile(String onOutOfMemoryVal) {
    String path = getScriptPathFromArg(onOutOfMemoryVal, OOME_NOTIFIER_SCRIPT_PREFIX);
    return path == null ? null : new File(path);
  }

  private static boolean copyOOMEscript(File scriptFile) {
    File scriptDirectory = scriptFile.getParentFile();

    // cleanup all stale process-specific generated files in the parent folder of the given OOME
    // notifier script
    runScriptCleanup(scriptDirectory);

    if (scriptDirectory.exists()) {
      // can be safely ignored; if the folder exists we will just reuse it
      if (!scriptDirectory.canWrite()) {
        LOG.warn(
            SEND_TELEMETRY,
            "Read only directory {}. OOME notification will not work properly.",
            scriptDirectory);
        return false;
      }
    } else {
      if (!scriptDirectory.mkdirs()) {
        LOG.warn(
            SEND_TELEMETRY,
            "Failed to create writable OOME script folder {}. OOME notification will not work properly.",
            scriptDirectory);
        return false;
      }
      scriptDirectory.setReadable(true, false);
      scriptDirectory.setWritable(true, false);
      scriptDirectory.setExecutable(true, false);
    }

    try {
      // do not overwrite existing
      if (!scriptFile.exists()) {
        copyStream(getOomeNotifierTemplate(), scriptFile);
      }
      scriptFile.setReadable(true, false);
      scriptFile.setWritable(false, false);
      scriptFile.setExecutable(true, false);
    } catch (IOException e) {
      LOG.warn(
          SEND_TELEMETRY,
          "Failed to copy OOME script {}. OOME notification will not work properly.",
          scriptFile);
      return false;
    }
    return true;
  }

  private static void copyStream(InputStream in, File dest) throws IOException {
    try (InputStream src = in;
        FileOutputStream out = new FileOutputStream(dest)) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = src.read(buf)) >= 0) {
        out.write(buf, 0, n);
      }
    }
  }

  private static void runScriptCleanup(File dir) {
    if (!dir.exists()) {
      return;
    }
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    Set<String> pidSet = null;
    for (File file : files) {
      if (!file.isFile()) {
        continue;
      }
      String pid = pidFromSpecialFileName(file.getName());
      if (pid != null && !pid.equals(PidHelper.getPid())) {
        if (pidSet == null) {
          // lazy init: forks jps to get the list of running Java PIDs
          pidSet = PidHelper.getJavaPids();
        }
        if (!pidSet.contains(pid)) {
          LOG.debug("Cleaning process specific file {}", file);
          file.delete();
        }
      }
    }
  }
}
