package datadog.crashtracking;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;
import static java.util.Comparator.reverseOrder;
import static java.util.Locale.ROOT;

import com.datadoghq.profiler.JVMAccess;
import com.sun.management.HotSpotDiagnosticMXBean;
import datadog.environment.OperatingSystem;
import datadog.libs.ddprof.DdprofLibraryLoader;
import datadog.trace.util.PidHelper;
import datadog.trace.util.TempLocationManager;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    try {
      FlagAccess access = null;
      if (forceJmx) {
        access =
            new JMXFlagAccess(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class));
      } else {
        DdprofLibraryLoader.JVMAccessHolder jvmAccessHolder = DdprofLibraryLoader.jvmAccess();
        Throwable reasonNotLoaded = jvmAccessHolder.getReasonNotLoaded();
        if (reasonNotLoaded != null) {
          LOG.debug(
              SEND_TELEMETRY,
              "Failed to load JVM access library: "
                  + jvmAccessHolder.getReasonNotLoaded().getMessage()
                  + ". Crash tracking will need to rely on user provided JVM arguments.");
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
      LOG.debug(SEND_TELEMETRY, "Failed to initialize crash tracking: " + t.getMessage(), t);
    }
    return false;
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
                      LOG.warn(SEND_TELEMETRY, "Failed deleting config file: " + cfgPath, e);
                    }
                  }));
      LOG.debug("Config file written: {}", cfgPath);
    } catch (IOException e) {
      LOG.warn(SEND_TELEMETRY, "Failed writing config file: " + cfgPath);
      try {
        Files.deleteIfExists(cfgPath);
      } catch (IOException ignored) {
        // ignore
      }
    }
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
    arg = arg.toLowerCase();
    int idx = arg.indexOf(scriptNamePrefix);
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
            "Unable to set OnError flag to " + onErrorVal + ". Crash-tracking may not work.");
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
            "Unable to set OnOutOfMemoryError flag to "
                + onOutOfMemoryVal
                + ". OOME tracking may not work.");
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
          msg
              + " ["
              + t.getMessage()
              + "] (Change the logging level to debug to see the full stacktrace)");
    }
  }
}
