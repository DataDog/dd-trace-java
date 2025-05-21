package com.datadog.crashtracking;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;
import static java.util.Comparator.reverseOrder;
import static java.util.Locale.ROOT;

import com.datadoghq.profiler.JVMAccess;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.PidHelper;
import datadog.trace.util.TempLocationManager;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Initializer {
  static final Logger LOG = LoggerFactory.getLogger(Initializer.class);
  static final String PID_PREFIX = "_pid";
  static final String RWXRWXRWX = "rwxrwxrwx";
  static final String R_XR_XR_X = "r-xr-xr-x";

  public static void initialize() {
    ConfigProvider cfgProvider = ConfigProvider.getInstance();
    String scratchDir = cfgProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH);

    JVMAccess jvmAccess =
        new JVMAccess(
            null,
            scratchDir,
            throwable -> {
              logInitializationError(
                  "Unexpected exception while initializing JVMAccess", throwable);
            });

    JVMAccess.Flags flags = jvmAccess.flags();

    initializeCrashUploader(flags);
    initializeOOMENotifier(flags);
  }

  static InputStream getCrashUploaderTemplate() {
    String name = Platform.isWindows() ? "upload_crash.bat" : "upload_crash.sh";
    return CrashUploader.class.getResourceAsStream(name);
  }

  static InputStream getOomeNotifierTemplate() {
    String name = Platform.isWindows() ? "notify_oome.bat" : "notify_oome.sh";
    return OOMENotifier.class.getResourceAsStream(name);
  }

  static String findAgentJar() {
    String agentPath = null;
    String classResourceName = CrashUploader.class.getName().replace('.', '/') + ".class";
    URL classResource = CrashUploader.class.getClassLoader().getResource(classResourceName);
    String selfClass = classResource == null ? "null" : classResource.toString();
    if (selfClass.startsWith("jar:file:")) {
      int idx = selfClass.lastIndexOf(".jar");
      if (idx > -1) {
        agentPath = selfClass.substring(9, idx + 4);
      }
    }
    // test harness env is different; use the known project structure to locate the agent jar
    else if (selfClass.startsWith("file:")) {
      int idx = selfClass.lastIndexOf("dd-java-agent");
      if (idx > -1) {
        Path libsPath = Paths.get(selfClass.substring(5, idx + 13), "build", "libs");
        try (Stream<Path> files = Files.walk(libsPath)) {
          Predicate<Path> isJarFile =
              p -> p.getFileName().toString().toLowerCase(ROOT).endsWith(".jar");
          agentPath =
              files
                  .sorted(reverseOrder())
                  .filter(isJarFile)
                  .findFirst()
                  .map(Path::toString)
                  .orElse(null);
        } catch (IOException ignored) {
          // Ignore failure to get agent path
        }
      }
    }
    return agentPath;
  }

  static void writeConfig(Path scriptPath, String... entries) {
    String cfgFileName = getBaseName(scriptPath) + PID_PREFIX + PidHelper.getPid() + ".cfg";
    Path cfgPath = scriptPath.resolveSibling(cfgFileName);
    LOG.debug("Writing config file: {}", cfgPath);
    try (BufferedWriter bw = Files.newBufferedWriter(cfgPath)) {
      for (int i = 0; i < entries.length; i += 2) {
        bw.write(entries[i]);
        bw.write('=');
        bw.write(entries[i + 1]);
        bw.newLine();
      }
      bw.write("java_home=" + System.getProperty("java.home"));
      bw.newLine();

      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  AGENT_THREAD_GROUP,
                  () -> {
                    try {
                      LOG.debug("Deleting config file: {}", cfgPath);
                      Files.deleteIfExists(cfgPath);
                    } catch (IOException e) {
                      LOG.warn("Failed deleting config file: {}", cfgPath, e);
                    }
                  }));
      LOG.debug("Config file written: {}", cfgPath);
    } catch (IOException e) {
      LOG.warn("Failed writing config file: {}", cfgPath);
      try {
        Files.deleteIfExists(cfgPath);
      } catch (IOException ignored) {
        // ignore
      }
    }
  }

  private static String getBaseName(Path path) {
    String filename = path.getFileName().toString();
    int dotIndex = filename.lastIndexOf('.');
    if (dotIndex == -1) {
      return filename;
    }
    return filename.substring(0, dotIndex);
  }

  /**
   * If the value of `-XX:OnError` JVM argument is referring to `dd_crash_uploader.sh` or
   * `dd_crash_uploader.bat` and the script does not exist it will be created and prefilled with
   * code ensuring the error log upload will be triggered on JVM crash.
   */
  private static void initializeCrashUploader(JVMAccess.Flags flags) {
    try {
      String onErrorVal = flags.getStringFlag("OnError");
      String onErrorFile = flags.getStringFlag("ErrorFile");

      String uploadScript = getScript("dd_crash_uploader");
      if (onErrorVal == null || onErrorVal.isEmpty()) {
        onErrorVal = uploadScript;
      } else if (!onErrorVal.contains("dd_crash_uploader")) {
        // we can chain scripts so let's preserve the original value in addition to our crash
        // uploader
        onErrorVal = uploadScript + "; " + onErrorVal;
      }

      // set the JVM flag
      flags.setStringFlag("OnError", onErrorVal);
      if (LOG.isDebugEnabled()) {
        String currentVal = flags.getStringFlag("OnError");
        if (!currentVal.equals(uploadScript)) {
          LOG.debug("Unable to set OnError flag to {}. Crash-tracking may not work.", currentVal);
        }
      }

      CrashUploaderScriptInitializer.initialize(uploadScript, onErrorFile);
    } catch (Throwable t) {
      logInitializationError(
          "Unexpected exception while creating custom crash upload script. Crash tracking will not work properly.",
          t);
    }
  }

  private static void initializeOOMENotifier(JVMAccess.Flags flags) {
    try {
      String onOutOfMemoryVal = flags.getStringFlag("OnOutOfMemoryError");
      String notifierScript = getScript("dd_oome_notifier");

      if (onOutOfMemoryVal == null || onOutOfMemoryVal.isEmpty()) {
        onOutOfMemoryVal = notifierScript;
      } else if (!onOutOfMemoryVal.contains("dd_oome_notifier")) {
        // we can chain scripts so let's preserve the original value in addition to our oome tracker
        onOutOfMemoryVal = notifierScript + "; " + onOutOfMemoryVal;
      }

      // set the JVM flag
      flags.setStringFlag("OnOutOfMemoryError", onOutOfMemoryVal);
      if (LOG.isDebugEnabled()) {
        String currentVal = flags.getStringFlag("OnOutOfMemoryError");
        if (!currentVal.equals(onOutOfMemoryVal)) {
          LOG.debug(
              "Unable to set OnOutOfMemoryError flag to {}. OOME tracking may not work.",
              currentVal);
        }
      }

      OOMENotifierScriptInitializer.initialize(notifierScript);
    } catch (Throwable t) {
      logInitializationError(
          "Unexpected exception while initializing OOME notifier. OOMEs will not be tracked.", t);
    }
  }

  private static String getScript(String scriptName) {
    return TempLocationManager.getInstance().getTempDir().toString()
        + "/"
        + getScriptFileName(scriptName)
        + " %p";
  }

  private static String getScriptFileName(String scriptName) {
    return scriptName + "." + (Platform.isWindows() ? "bat" : "sh");
  }

  private static void logInitializationError(String msg, Throwable t) {
    if (LOG.isDebugEnabled()) {
      LOG.warn("{}", msg, t);
    } else {
      LOG.warn(
          "{} [{}] (Change the logging level to debug to see the full stacktrace)",
          msg,
          t.getMessage());
    }
  }
}
