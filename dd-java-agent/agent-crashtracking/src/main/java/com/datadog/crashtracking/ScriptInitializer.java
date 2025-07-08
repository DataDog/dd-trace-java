package com.datadog.crashtracking;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;
import static java.util.Comparator.reverseOrder;
import static java.util.Locale.ROOT;

import com.sun.management.HotSpotDiagnosticMXBean;
import datadog.environment.OperatingSystem;
import datadog.trace.util.PidHelper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScriptInitializer {
  static final Logger LOG = LoggerFactory.getLogger(ScriptInitializer.class);
  static final String PID_PREFIX = "_pid";
  static final String RWXRWXRWX = "rwxrwxrwx";
  static final String R_XR_XR_X = "r-xr-xr-x";

  public static void initialize() {
    // this is HotSpot specific implementation (eg. will not work for IBM J9)
    HotSpotDiagnosticMXBean diagBean =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

    initializeCrashUploader(diagBean);
    initializeOOMENotifier(diagBean);
  }

  static InputStream getCrashUploaderTemplate() {
    String name = OperatingSystem.isWindows() ? "upload_crash.bat" : "upload_crash.sh";
    return CrashUploader.class.getResourceAsStream(name);
  }

  static InputStream getOomeNotifierTemplate() {
    String name = OperatingSystem.isWindows() ? "notify_oome.bat" : "notify_oome.sh";
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
  private static void initializeCrashUploader(HotSpotDiagnosticMXBean diagBean) {
    try {
      String onErrorVal = diagBean.getVMOption("OnError").getValue();
      String onErrorFile = diagBean.getVMOption("ErrorFile").getValue();
      CrashUploaderScriptInitializer.initialize(onErrorVal, onErrorFile);
    } catch (Throwable t) {
      logInitializationError(
          "Unexpected exception while creating custom crash upload script. Crash tracking will not work properly.",
          t);
    }
  }

  private static void initializeOOMENotifier(HotSpotDiagnosticMXBean diagBean) {
    try {
      String onOutOfMemoryVal = diagBean.getVMOption("OnOutOfMemoryError").getValue();
      OOMENotifierScriptInitializer.initialize(onOutOfMemoryVal);
    } catch (Throwable t) {
      logInitializationError(
          "Unexpected exception while initializing OOME notifier. OOMEs will not be tracked.", t);
    }
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
