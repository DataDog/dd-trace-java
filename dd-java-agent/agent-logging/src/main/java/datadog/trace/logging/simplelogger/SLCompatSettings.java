package datadog.trace.logging.simplelogger;

import datadog.trace.logging.LogLevel;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Properties;

/** Settings that provide the same configurable options as {@code SimpleLogger} from SLF4J. */
public class SLCompatSettings {

  public static final class Keys {
    // This is the package name that the shaded SimpleLogger had before. Use that for compatibility.
    private static final String PREFIX = "datadog.slf4j.simpleLogger.";

    public static final String LOG_KEY_PREFIX = PREFIX + "log.";
    // This setting does not change anything in the behavior as of slf4j 1.7.30 so we ignore it
    // public static final String CACHE_OUTPUT_STREAM = PREFIX + "cacheOutputStream";
    public static final String WARN_LEVEL_STRING = PREFIX + "warnLevelString";
    public static final String LEVEL_IN_BRACKETS = PREFIX + "levelInBrackets";
    public static final String LOG_FILE = PREFIX + "logFile";
    public static final String SHOW_SHORT_LOG_NAME = PREFIX + "showShortLogName";
    public static final String SHOW_LOG_NAME = PREFIX + "showLogName";
    public static final String SHOW_THREAD_NAME = PREFIX + "showThreadName";
    public static final String DATE_TIME_FORMAT = PREFIX + "dateTimeFormat";
    public static final String SHOW_DATE_TIME = PREFIX + "showDateTime";
    public static final String DEFAULT_LOG_LEVEL = PREFIX + "defaultLogLevel";

    // This is not available in SimpleLogger, but added here to simplify testing.
    static final String CONFIGURATION_FILE = PREFIX + "configurationFile";
  }

  public static final class Defaults {
    // This setting does not change anything in the behavior as of slf4j 1.7.30 so we ignore it
    // static final boolean CACHE_OUTPUT_STREAM = false;
    public static final boolean LEVEL_IN_BRACKETS = false;
    public static final String LOG_FILE = "System.err";
    public static final boolean SHOW_SHORT_LOG_NAME = false;
    public static final boolean SHOW_LOG_NAME = true;
    public static final boolean SHOW_THREAD_NAME = true;
    public static final String DATE_TIME_FORMAT = null;
    public static final boolean SHOW_DATE_TIME = false;
    public static final String DEFAULT_LOG_LEVEL = "INFO";

    public static final String CONFIGURATION_FILE = "simplelogger.properties";
  }

  static PrintStream getPrintStream(String logFile) {
    switch (logFile.toLowerCase()) {
      case "system.err":
        return System.err;
      case "system.out":
        return System.out;
      default:
        try {
          FileOutputStream outputStream = new FileOutputStream(logFile);
          PrintStream printStream = new PrintStream(outputStream);
          return printStream;
        } catch (FileNotFoundException | SecurityException e) {
          // TODO maybe have support for delayed logging of early failures?
          return System.err;
        }
    }
  }

  private static final class ResourceStreamPrivilegedAction
      implements PrivilegedAction<InputStream> {
    private final String fileName;

    public ResourceStreamPrivilegedAction(String fileName) {
      this.fileName = fileName;
    }

    @Override
    public InputStream run() {
      ClassLoader threadCL = Thread.currentThread().getContextClassLoader();
      if (threadCL != null) {
        return threadCL.getResourceAsStream(fileName);
      } else {
        return ClassLoader.getSystemResourceAsStream(fileName);
      }
    }
  }

  static Properties loadProperties(final String fileName) {
    Properties fileProperties = null;
    // Load properties in the same way that SimpleLogger does
    InputStream inputStream =
        AccessController.doPrivileged(new ResourceStreamPrivilegedAction(fileName));

    if (inputStream != null) {
      try {
        Properties properties = new Properties();
        properties.load(inputStream);
        fileProperties = properties;
        inputStream.close();
      } catch (java.io.IOException e) {
        // ignored
      }
    }

    return fileProperties;
  }

  private static String getString(
      Properties properties, Properties fallbackProperties, String name) {
    String property = properties == null ? null : properties.getProperty(name);
    if (property == null) {
      property = fallbackProperties == null ? null : fallbackProperties.getProperty(name);
    }
    return property;
  }

  static String getString(
      Properties properties, Properties fallbackProperties, String name, String defaultValue) {
    String property = getString(properties, fallbackProperties, name);
    return property == null ? defaultValue : property;
  }

  static boolean getBoolean(
      Properties properties, Properties fallbackProperties, String name, boolean defaultValue) {
    String property = getString(properties, fallbackProperties, name);
    return property == null ? defaultValue : Boolean.parseBoolean(property);
  }

  private static DateFormat getDateTimeFormatter(String dateTimeFormat) {
    if (dateTimeFormat == null) {
      return null;
    }
    DateFormat dateTimeFormatter = null;
    try {
      dateTimeFormatter = new SimpleDateFormat(dateTimeFormat);
    } catch (IllegalArgumentException e) {
      // TODO maybe have support for delayed logging of early failures?
    }
    return dateTimeFormatter;
  }

  private final Properties properties;
  private final Properties fileProperties;

  // Package reachable for SLCompatHelper and tests
  final String warnLevelString;
  final boolean levelInBrackets;
  final PrintStream printStream;
  final boolean showShortLogName;
  final boolean showLogName;
  final boolean showThreadName;
  final DateFormat dateTimeFormatter;
  final boolean showDateTime;
  final LogLevel defaultLogLevel;

  public SLCompatSettings(Properties properties) {
    this(
        properties,
        loadProperties(
            properties.getProperty(Keys.CONFIGURATION_FILE, Defaults.CONFIGURATION_FILE)));
  }

  public SLCompatSettings(Properties properties, Properties fileProperties) {
    this(
        properties,
        fileProperties,
        getPrintStream(getString(properties, fileProperties, Keys.LOG_FILE, Defaults.LOG_FILE)));
  }

  public SLCompatSettings(
      Properties properties, Properties fileProperties, PrintStream printStream) {
    this(
        properties,
        fileProperties,
        getString(properties, fileProperties, Keys.WARN_LEVEL_STRING),
        getBoolean(properties, fileProperties, Keys.LEVEL_IN_BRACKETS, Defaults.LEVEL_IN_BRACKETS),
        printStream,
        getBoolean(
            properties, fileProperties, Keys.SHOW_SHORT_LOG_NAME, Defaults.SHOW_SHORT_LOG_NAME),
        getBoolean(properties, fileProperties, Keys.SHOW_LOG_NAME, Defaults.SHOW_LOG_NAME),
        getBoolean(properties, fileProperties, Keys.SHOW_THREAD_NAME, Defaults.SHOW_THREAD_NAME),
        getDateTimeFormatter(
            getString(
                properties, fileProperties, Keys.DATE_TIME_FORMAT, Defaults.DATE_TIME_FORMAT)),
        getBoolean(properties, fileProperties, Keys.SHOW_DATE_TIME, Defaults.SHOW_DATE_TIME),
        LogLevel.fromString(
            getString(
                properties, fileProperties, Keys.DEFAULT_LOG_LEVEL, Defaults.DEFAULT_LOG_LEVEL)));
  }

  public SLCompatSettings(
      Properties properties,
      Properties fileProperties,
      String warnLevelString,
      boolean levelInBrackets,
      PrintStream printStream,
      boolean showShortLogName,
      boolean showLogName,
      boolean showThreadName,
      DateFormat dateTimeFormatter,
      boolean showDateTime,
      LogLevel defaultLogLevel) {
    this.properties = properties;
    this.fileProperties = fileProperties;
    this.warnLevelString = warnLevelString;
    this.levelInBrackets = levelInBrackets;
    this.printStream = printStream;
    this.showShortLogName = showShortLogName;
    this.showLogName = showLogName;
    this.showThreadName = showThreadName;
    this.dateTimeFormatter = dateTimeFormatter;
    this.showDateTime = showDateTime;
    this.defaultLogLevel = defaultLogLevel;
  }

  String getString(String name) {
    return getString(properties, fileProperties, name);
  }

  public LogLevel logLevelForName(String name) {
    String level = null;
    String remainder = name;
    int end = name.length();
    while (level == null && end > -1) {
      remainder = remainder.substring(0, end);
      level = getString(Keys.LOG_KEY_PREFIX + remainder);
      end = remainder.lastIndexOf('.');
    }
    return level != null ? LogLevel.fromString(level) : defaultLogLevel;
  }

  public String logNameForName(String name) {
    if (showShortLogName) {
      return name.substring(name.lastIndexOf(".") + 1);
    } else if (showLogName) {
      return name;
    } else {
      return "";
    }
  }
}
