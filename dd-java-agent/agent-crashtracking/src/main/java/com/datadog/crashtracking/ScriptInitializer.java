package com.datadog.crashtracking;

import com.sun.management.HotSpotDiagnosticMXBean;
import datadog.trace.api.Config;
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
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScriptInitializer {
  private static final Logger log = LoggerFactory.getLogger(ScriptInitializer.class);
  private static final Pattern oomeNotifierScriptPattern =
      Pattern.compile("(.*?dd_oome_notifier[.](sh|bat))\\s+(%p)", Pattern.CASE_INSENSITIVE);
  private static final String PID_PREFIX = "_pid";

  private static class ScriptCleanupVisitor implements FileVisitor<Path> {
    private static final Pattern PID_PATTERN = Pattern.compile(".*?" + PID_PREFIX + "(\\d+)");

    private final Set<String> pidSet = PidHelper.getJavaPids();

    static void run(Path dir) {
      try {
        if (Files.exists(dir)) {
          Files.walkFileTree(dir, new ScriptCleanupVisitor());
        }
      } catch (IOException e) {
        log.warn("Failed cleaning up process specific files in {}", dir, e);
      }
    }

    private ScriptCleanupVisitor() {}

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      String fileName = file.getFileName().toString();
      Matcher matcher = PID_PATTERN.matcher(fileName);
      if (matcher.find()) {
        String pid = matcher.group(1);
        if (pid != null && !pid.equals(PidHelper.getPid()) && !pidSet.contains(pid)) {
          log.debug("Cleaning process specific file {}", file);
          Files.delete(file);
        }
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      log.debug("Failed to delete file {}", file, exc);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      return FileVisitResult.CONTINUE;
    }
  }

  public static void initialize() {
    // this is HotSpot specific implementation (eg. will not work for IBM J9)
    HotSpotDiagnosticMXBean diagBean =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

    initializeCrashUploader(diagBean);
    initializeOOMENotifier(diagBean);
  }

  private static void writeConfig(Path cfgPath, String... entries) {
    log.debug("Writing config file: {}", cfgPath);
    try (BufferedWriter bw = Files.newBufferedWriter(cfgPath)) {
      for (int i = 0; i < entries.length; i += 2) {
        bw.write(entries[i]);
        bw.write("=");
        bw.write(entries[i + 1]);
        bw.newLine();
      }
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      log.debug("Deleting config file: {}", cfgPath);
                      Files.deleteIfExists(cfgPath);
                    } catch (IOException e) {
                      log.warn("Failed deleting config file: {}", cfgPath, e);
                    }
                  }));
      log.debug("Config file written: {}", cfgPath);
    } catch (IOException e) {
      log.warn("Failed writing config file: {}", cfgPath, e);
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
      initializeCrashUploader(onErrorVal, onErrorFile);
    } catch (Throwable t) {
      log.warn(
          "Failed creating custom crash upload script. Crash tracking will not work properly.", t);
    }
  }

  // @VisibleForTests
  static void initializeCrashUploader(String onErrorVal, String onErrorFile) throws IOException {
    if (onErrorVal == null || onErrorVal.isEmpty()) {
      log.debug("'-XX:OnError' argument was not provided. Crash tracking is disabled.");
      return;
    }
    if (onErrorFile == null || onErrorFile.isEmpty()) {
      onErrorFile = System.getProperty("user.dir") + "/hs_err_pid" + PidHelper.getPid() + ".log";
      log.debug("No -XX:ErrorFile value, defaulting to {}", onErrorFile);
    } else {
      onErrorFile = Strings.replace(onErrorFile, "%p", PidHelper.getPid());
    }

    String agentJar = findAgentJar();
    if (agentJar == null) {
      log.warn("Unable to locate the agent jar. Crash tracking will not work properly.");
      return;
    }

    Path scriptPath = Paths.get(onErrorVal.replace(" %p", ""));
    if (scriptPath
        .getFileName()
        .toString()
        .toLowerCase(Locale.ROOT)
        .contains("dd_crash_uploader")) {
      try {
        Files.createDirectories(
            scriptPath.getParent(),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
      } catch (FileAlreadyExistsException ignored) {
        // can be safely ignored; if the folder exists we will just reuse it
      }
      log.debug("Writing crash uploader script: {}", scriptPath);
      writeScript(getCrashUploaderTemplate(), scriptPath, agentJar, onErrorFile);
    }
    Path cfgPath =
        scriptPath.resolveSibling(
            getBaseName(scriptPath) + PID_PREFIX + PidHelper.getPid() + ".cfg");
    writeConfig(cfgPath, "agent", agentJar, "hs_err", onErrorFile);
  }

  private static void initializeOOMENotifier(HotSpotDiagnosticMXBean diagBean) {
    try {
      String onOutOfMemoryVal = diagBean.getVMOption("OnOutOfMemoryError").getValue();
      initializeOOMENotifier(onOutOfMemoryVal);
    } catch (Throwable t) {
      log.warn("Failed initializing OOME notifier. OOMEs will not be tracked.", t);
    }
  }

  // @VisibleForTests
  static void initializeOOMENotifier(String onOutOfMemoryVal) throws IOException {
    if (onOutOfMemoryVal == null || onOutOfMemoryVal.isEmpty()) {
      log.debug("'-XX:OnOutOfMemoryError' argument was not provided. OOME tracking is disabled.");
      return;
    }
    Matcher m = oomeNotifierScriptPattern.matcher(onOutOfMemoryVal);
    if (!m.find()) {
      log.info(
          "OOME notifier script value ({}) does not follow the expected format: <path>/dd_ome_notifier.(sh|bat) %p. OOME tracking is disabled.",
          onOutOfMemoryVal);
      return;
    }

    String tags =
        Config.get().getMergedJmxTags().entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .collect(Collectors.joining(","));
    Path scriptPath = Paths.get(m.group(1));

    // cleanup all stale process-specific generated files in the parent folder of the given OOME
    // notifier script
    ScriptCleanupVisitor.run(scriptPath.getParent());

    try {
      Files.createDirectories(
          scriptPath.getParent(),
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
    } catch (FileAlreadyExistsException ignored) {
      // can be safely ignored; if the folder exists we will just reuse it
    }
    Files.copy(getOomeNotifierTemplate(), scriptPath, StandardCopyOption.REPLACE_EXISTING);
    Files.setPosixFilePermissions(scriptPath, PosixFilePermissions.fromString("r-xr-xr-x"));

    String agentJar = findAgentJar();
    if (agentJar == null) {
      log.warn("Unable to locate the agent jar. OOME notification will not work properly.");
      return;
    }
    Path cfgPath =
        scriptPath.resolveSibling(
            getBaseName(scriptPath) + PID_PREFIX + PidHelper.getPid() + ".cfg");
    writeConfig(cfgPath, "agent", agentJar, "tags", tags);
  }

  private static void writeScript(
      InputStream template, Path scriptPath, String execClass, String crashFile)
      throws IOException {
    if (!Files.exists(scriptPath)) {
      try (BufferedReader br = new BufferedReader(new InputStreamReader(template))) {
        try (BufferedWriter bw = Files.newBufferedWriter(scriptPath)) {
          br.lines()
              .map(
                  line -> {
                    line = Strings.replace(line, "!AGENT_JAR!", execClass);
                    if (crashFile != null) {
                      line = Strings.replace(line, "!JAVA_ERROR_FILE!", crashFile);
                    }
                    return line;
                  })
              .forEach(line -> writeLine(bw, line));
        }
      }
      Files.setPosixFilePermissions(scriptPath, PosixFilePermissions.fromString("r-xr-xr-x"));
    }
  }

  private static void writeLine(BufferedWriter bw, String line) {
    try {
      bw.write(line);
      bw.newLine();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static InputStream getCrashUploaderTemplate() {
    String name = Platform.isWindows() ? "upload_crash.bat" : "upload_crash.sh";
    return CrashUploader.class.getResourceAsStream(name);
  }

  private static InputStream getOomeNotifierTemplate() {
    String name = Platform.isWindows() ? "notify_oome.bat" : "notify_oome.sh";
    return OOMENotifier.class.getResourceAsStream(name);
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
                    .orElseThrow(() -> new IOException("Missing CLI jar"))
                    .toString();
          }
        }
      }
    }
    return agentPath;
  }
}
