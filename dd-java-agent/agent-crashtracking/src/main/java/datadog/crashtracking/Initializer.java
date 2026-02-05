package datadog.crashtracking;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.util.Comparator.reverseOrder;
import static java.util.Locale.ROOT;

import com.datadoghq.profiler.JVMAccess;
import com.sun.management.HotSpotDiagnosticMXBean;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.api.Platform;
import datadog.trace.util.TempLocationManager;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.StringTokenizer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Initializer {
  static final Logger LOG = LoggerFactory.getLogger(Initializer.class);
  static final String PID_PREFIX = "_pid";
  static final String RWXRWXRWX = "rwxrwxrwx";
  static final String R_XR_XR_X = "r-xr-xr-x";

  private interface FlagAccess {
    String getValue(String flagName);

    boolean setValue(String flagName, String value);
  }

  private static final class JVMFlagAccess implements FlagAccess {
    private final JVMAccess.Flags flags;

    JVMFlagAccess(JVMAccess.Flags flags) {
      this.flags = flags;
    }

    @Override
    public String getValue(String flagName) {
      return flags.getStringFlag(flagName);
    }

    @Override
    public boolean setValue(String flagName, String value) {
      flags.setStringFlag(flagName, value);
      return flags.getStringFlag(flagName).equals(value);
    }
  }

  private static final class JMXFlagAccess implements FlagAccess {
    private final HotSpotDiagnosticMXBean diagBean;

    JMXFlagAccess(HotSpotDiagnosticMXBean diagBean) {
      this.diagBean = diagBean;
    }

    @Override
    public String getValue(String flagName) {
      return diagBean.getVMOption(flagName).getValue();
    }

    @Override
    public boolean setValue(String flagName, String value) {
      // cannot really set the underlying JVM flag value
      // let's pretend everything went just fine
      return true;
    }
  }

  public static boolean initialize(boolean forceJmx) {
    // J9/OpenJ9 requires different initialization path
    if (JavaVirtualMachine.isJ9()) {
      return initializeJ9();
    }

    try {
      FlagAccess access = null;
      // Native images don't support the native ddprof library, use JMX instead
      if (forceJmx || Platform.isNativeImage()) {
        access =
            new JMXFlagAccess(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class));
      } else {
        DdprofLibraryLoader.JVMAccessHolder jvmAccessHolder = DdprofLibraryLoader.jvmAccess();
        Throwable reasonNotLoaded = jvmAccessHolder.getReasonNotLoaded();
        if (reasonNotLoaded != null) {
          LOG.debug(
              SEND_TELEMETRY,
              "Failed to load JVM access library: {}. Crash tracking will need to rely on user provided JVM arguments.",
              jvmAccessHolder.getReasonNotLoaded().getMessage());
          return false;
        } else {
          JVMAccess.Flags flags = jvmAccessHolder.getComponent().flags();
          access = new JVMFlagAccess(flags);
        }
      }

      initializeCrashUploader(access);
      initializeOOMENotifier(access);
      return true;
    } catch (Throwable t) {
      LOG.debug("Failed to initialize crash tracking: {}", t.getMessage(), t);
    }
    return false;
  }

  /**
   * Initialize crash tracking for J9/OpenJ9 JVMs.
   *
   * <p>Unlike HotSpot, J9's -Xdump option cannot be modified at runtime. This method:
   *
   * <ol>
   *   <li>Deploys the crash uploader script
   *   <li>Checks if -Xdump:tool is already configured
   *   <li>Logs instructions for manual configuration if not configured
   * </ol>
   *
   * @return true if -Xdump is properly configured, false otherwise
   */
  private static boolean initializeJ9() {
    try {
      String scriptPath = getJ9CrashUploaderScriptPath();

      // Check if -Xdump:tool is already configured via JVM arguments
      boolean xdumpConfigured = isXdumpToolConfigured();
      // Get custom javacore path if configured
      String javacorePath = getJ9JavacorePath();

      if (xdumpConfigured) {
        LOG.debug("J9 crash tracking: -Xdump:tool already configured, crash uploads enabled");
        // Initialize the crash uploader script and config manager
        CrashUploaderScriptInitializer.initialize(scriptPath, null, javacorePath);
        // Also set up OOME notifier script
        String oomeScript = getScript("dd_oome_notifier");
        OOMENotifierScriptInitializer.initialize(oomeScript);
        return true;
      } else {
        // Log instructions for manual configuration
        LOG.info("J9 JVM detected. To enable crash tracking, add this JVM argument at startup:");
        LOG.info("  -Xdump:tool:events=gpf+abort,exec={}\\ %pid", scriptPath);
        LOG.info(
            "Crash tracking will not be active until this argument is added and JVM is restarted.");
        // Still deploy the script so it's ready when user adds the argument
        CrashUploaderScriptInitializer.initialize(scriptPath, null, javacorePath);
        return false;
      }
    } catch (Throwable t) {
      logInitializationError(
          "Unexpected exception while initializing J9 crash tracking. Crash tracking will not work.",
          t);
    }
    return false;
  }

  /**
   * Get the custom javacore file path from -Xdump:java:file=... JVM argument.
   *
   * @return the custom javacore path, or null if not configured
   */
  private static String getJ9JavacorePath() {
    List<String> vmArgs = JavaVirtualMachine.getVmOptions();
    for (String arg : vmArgs) {
      if (arg.startsWith("-Xdump:java:file=") || arg.startsWith("-Xdump:java+heap:file=")) {
        int fileIdx = arg.indexOf("file=");
        if (fileIdx >= 0) {
          String path = arg.substring(fileIdx + 5);
          // Handle comma-separated options: -Xdump:java:file=/path,request=exclusive
          int commaIdx = path.indexOf(',');
          if (commaIdx > 0) {
            path = path.substring(0, commaIdx);
          }
          return path;
        }
      }
    }
    return null;
  }

  /**
   * Check if -Xdump:tool is configured with our crash uploader script.
   *
   * <p>Looks for JVM arguments matching: -Xdump:tool:events=...,exec=...dd_crash_uploader...
   */
  private static boolean isXdumpToolConfigured() {
    List<String> vmArgs = JavaVirtualMachine.getVmOptions();
    for (String arg : vmArgs) {
      if (arg.startsWith("-Xdump:tool") && arg.contains("dd_crash_uploader")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get the path where the crash uploader script should be deployed for J9.
   *
   * <p>Note: The actual script deployment is handled by {@link CrashUploaderScriptInitializer} when
   * initialize() is called with this path.
   *
   * @return the full path for the crash uploader script
   */
  private static String getJ9CrashUploaderScriptPath() {
    String scriptFileName = getScriptFileName("dd_crash_uploader");
    Path scriptPath = TempLocationManager.getInstance().getTempDir().resolve(scriptFileName);
    return scriptPath.toString();
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

  static String pidFromSpecialFileName(String fileName) {
    if (fileName == null || fileName.isEmpty()) {
      return null;
    }
    int index = fileName.indexOf(PID_PREFIX);
    if (index < 0) {
      return null; // not a process specific file
    }
    int pos = index + PID_PREFIX.length();
    int startPos = pos;

    // check if the file name contains a PID
    if (fileName.length() <= pos) {
      return null; // no PID in the file name
    }
    // extract the PID from the file name
    // eg. pid_12345.log -> 12345
    while (pos < fileName.length() && Character.isDigit(fileName.charAt(pos))) {
      pos++;
    }
    return fileName.substring(startPos, pos);
  }

  static String getScriptPathFromArg(String arg, String scriptNamePrefix) {
    if (arg == null || arg.isEmpty()) {
      return null;
    }
    int idx = arg.toLowerCase().indexOf(scriptNamePrefix);
    if (idx < 0) {
      // the script name is not present in the value, so we cannot extract the path
      return null;
    }
    // the script name is present, so we can extract the path

    char ch;
    idx += scriptNamePrefix.length();
    while (idx < arg.length() && (ch = arg.charAt(idx)) != ' ' && ch != ';') {
      idx++;
    }
    String path = arg.substring(0, idx);
    idx = path.lastIndexOf(';'); // the arg may contain multiple commands separated by semicolons
    if (idx >= 0) {
      // if there is a semicolon, we take the part after it and trim it
      path = path.substring(idx + 1).trim();
    }
    return path;
  }

  /**
   * If the value of `-XX:OnError` JVM argument is referring to `dd_crash_uploader.sh` or
   * `dd_crash_uploader.bat` and the script does not exist it will be created and prefilled with
   * code ensuring the error log upload will be triggered on JVM crash.
   */
  private static void initializeCrashUploader(FlagAccess flags) {
    try {
      String onErrorVal = flags.getValue("OnError");
      String onErrorFile = flags.getValue("ErrorFile");

      String uploadScript = getScript("dd_crash_uploader");
      if (onErrorVal == null || onErrorVal.isEmpty()) {
        onErrorVal = uploadScript;
      } else if (!onErrorVal.contains("dd_crash_uploader")) {
        // we can chain scripts so let's preserve the original value in addition to our crash
        // uploader
        onErrorVal = uploadScript + "; " + onErrorVal;
      } else {
        StringTokenizer st = new StringTokenizer(onErrorVal, ";");
        while (st.hasMoreTokens()) {
          String part = st.nextToken();
          if (part.trim().contains("dd_crash_uploader")) {
            // reuse the existing script name
            uploadScript = part.trim().replace(" %p", "");
            break;
          }
        }
      }

      // set the JVM flag
      boolean rslt = flags.setValue("OnError", onErrorVal);
      if (!rslt && LOG.isDebugEnabled()) {
        LOG.debug(
            SEND_TELEMETRY,
            "Unable to set OnError flag to {}. Crash-tracking may not work.",
            onErrorVal);
      }

      CrashUploaderScriptInitializer.initialize(uploadScript, onErrorFile);
    } catch (Throwable t) {
      logInitializationError(
          "Unexpected exception while creating custom crash upload script. Crash tracking will not work properly.",
          t);
    }
  }

  private static void initializeOOMENotifier(FlagAccess flags) {
    try {
      String onOutOfMemoryVal = flags.getValue("OnOutOfMemoryError");

      String notifierScript = getScript("dd_oome_notifier");

      if (onOutOfMemoryVal == null || onOutOfMemoryVal.isEmpty()) {
        onOutOfMemoryVal = notifierScript;
      } else if (!onOutOfMemoryVal.contains("dd_oome_notifier")) {
        // we can chain scripts so let's preserve the original value in addition to our oome tracker
        onOutOfMemoryVal = notifierScript + "; " + onOutOfMemoryVal;
      } else {
        StringTokenizer st = new StringTokenizer(onOutOfMemoryVal, ";");
        while (st.hasMoreTokens()) {
          String part = st.nextToken();
          if (part.trim().contains("dd_oome_notifier")) {
            // reuse the existing script name
            notifierScript = part.trim();
            break;
          }
        }
      }

      // set the JVM flag
      boolean rslt = flags.setValue("OnOutOfMemoryError", onOutOfMemoryVal);
      if (!rslt && LOG.isDebugEnabled()) {
        LOG.debug(
            SEND_TELEMETRY,
            "Unable to set OnOutOfMemoryError flag to {}. OOME tracking may not work.",
            onOutOfMemoryVal);
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
    return scriptName + "." + (OperatingSystem.isWindows() ? "bat" : "sh");
  }

  private static void logInitializationError(String msg, Throwable t) {
    if (LOG.isDebugEnabled()) {
      LOG.warn(SEND_TELEMETRY, msg, t);
    } else {
      LOG.warn(
          SEND_TELEMETRY,
          "{} [{}] (Change the logging level to debug to see the full stacktrace)",
          msg,
          t.getMessage());
    }
  }
}
