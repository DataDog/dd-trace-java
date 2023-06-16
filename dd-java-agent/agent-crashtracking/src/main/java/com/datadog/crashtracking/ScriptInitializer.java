package com.datadog.crashtracking;

import com.sun.management.HotSpotDiagnosticMXBean;
import datadog.trace.api.Platform;
import datadog.trace.util.PidHelper;
import datadog.trace.util.Strings;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScriptInitializer {
  private static final Logger log = LoggerFactory.getLogger(ScriptInitializer.class);

  /**
   * If the value of `-XX:OnError` JVM argument is referring to `dd_crash_uploader.sh` or
   * `dd_crash_uploader.bat` and the script does not exist it will be created and prefilled with
   * code ensuring the error log upload will be triggered on JVM crash.
   */
  public static void initialize() {
    try {
      // this is HotSpot specific implementation (eg. will not work for IBM J9)
      HotSpotDiagnosticMXBean diagBean =
          ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      String onErrorVal = diagBean.getVMOption("OnError").getValue();
      String onErrorFile =
          Strings.replace(diagBean.getVMOption("ErrorFile").getValue(), "%p", PidHelper.getPid());
      initialize(onErrorVal, onErrorFile);
    } catch (Throwable t) {
      log.warn(
          "Failed creating custom crash upload script. Crash tracking will not work properly.", t);
    }
  }

  // @VisibleForTests
  static void initialize(String onErrorVal, String onErrorFile) throws IOException {
    if (onErrorVal == null || onErrorVal.isEmpty()) {
      log.debug("'-XX:OnError' argument was not provided. Crash tracking is disabled.");
      return;
    }
    if (onErrorFile == null || onErrorFile.isEmpty()) {
      onErrorFile = System.getProperty("user.dir") + "/hs_err_pid" + PidHelper.getPid() + ".log";
      log.debug("No -XX:ErrorFile value, defaulting to {}", onErrorFile);
    }
    Path scriptPath = Paths.get(onErrorVal);
    if (scriptPath.getFileName().toString().toLowerCase(Locale.ROOT).contains("dd_crash_uploader")
        && Files.notExists(scriptPath)) {
      try {
        Files.createDirectories(scriptPath.getParent());
      } catch (FileAlreadyExistsException ignored) {
        // can be safely ignored; if the folder exists we will just reuse it
      }
      String agentJar = findAgentJar();
      if (agentJar == null) {
        log.warn("Unable to locate the agent jar. Crash tracking will not work properly.");
        return;
      }
      writeScript(onErrorFile, agentJar, scriptPath);
    }
  }

  private static void writeScript(String crashFile, String execClass, Path scriptPath)
      throws IOException {
    log.debug("Writing crash uploader script: {}", scriptPath);
    try (BufferedReader br = new BufferedReader(new InputStreamReader(getScriptData()))) {
      try (BufferedWriter bw = Files.newBufferedWriter(scriptPath)) {
        br.lines()
            .map(
                line ->
                    Strings.replace(
                        Strings.replace(line, "!AGENT_JAR!", execClass),
                        "!JAVA_ERROR_FILE!",
                        crashFile))
            .forEach(line -> writeLine(bw, line));
      }
    }
    Files.setPosixFilePermissions(scriptPath, PosixFilePermissions.fromString("r-xr-x---"));
  }

  private static void writeLine(BufferedWriter bw, String line) {
    try {
      bw.write(line);
      bw.newLine();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static InputStream getScriptData() {
    String name = Platform.isWindows() ? "upload_crash.bat" : "upload_crash.sh";
    return CrashUploader.class.getResourceAsStream(name);
  }

  private static String findAgentJar() throws IOException {
    String agentPath = null;
    String selfClass =
        CrashUploader.class
            .getClassLoader()
            .getResource(CrashUploader.class.getName().replace('.', '/') + ".class")
            .toString();
    if (selfClass.startsWith("jar:file:")) {
      int idx = selfClass.lastIndexOf(".jar");
      if (idx > -1) {
        agentPath = selfClass.substring(9, idx + 4);
      }
    } else {
      // test harness env is different; use the known project structure to locate the agent jar
      if (selfClass.startsWith("file:")) {
        int idx = selfClass.lastIndexOf("dd-java-agent");
        if (idx > -1) {
          Path libsPath = Paths.get(selfClass.substring(5, idx + 13), "build", "libs");
          try (Stream<Path> files = Files.walk(libsPath)) {
            agentPath =
                files
                    .sorted(Comparator.reverseOrder())
                    .filter(
                        p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .findFirst()
                    .toString();
          }
        }
      }
    }
    return agentPath;
  }
}
