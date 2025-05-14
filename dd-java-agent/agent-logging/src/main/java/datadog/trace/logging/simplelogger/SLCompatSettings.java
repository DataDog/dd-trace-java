package datadog.trace.logging.simplelogger;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.LogReporter;
import datadog.trace.logging.PrintStreamWrapper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Settings that provide the same configurable options as {@code SimpleLogger} from SLF4J. */
public class SLCompatSettings {

  public static final class Names {
    public static final String WARN_LEVEL_STRING = "warnLevelString";
    public static final String LEVEL_IN_BRACKETS = "levelInBrackets";
    public static final String LOG_FILE = "logFile";
    public static final String SHOW_SHORT_LOG_NAME = "showShortLogName";
    public static final String SHOW_LOG_NAME = "showLogName";
    public static final String SHOW_THREAD_NAME = "showThreadName";
    public static final String DATE_TIME_FORMAT = "dateTimeFormat";
    public static final String SHOW_DATE_TIME = "showDateTime";
    public static final String JSON_ENABLED = "jsonEnabled";
    public static final String DEFAULT_LOG_LEVEL = "defaultLogLevel";
    public static final String EMBED_EXCEPTION = "embedException";
    public static final String CONFIGURATION_FILE = "configurationFile";
  }

  public static final class Keys {
    // This is the package name that the shaded SimpleLogger had before. Use that for compatibility.
    private static final String PREFIX = "datadog.slf4j.simpleLogger.";

    public static final String LOG_KEY_PREFIX = PREFIX + "log.";
    // This setting does not change anything in the behavior as of slf4j 1.7.30 so we ignore it
    // public static final String CACHE_OUTPUT_STREAM = PREFIX + "cacheOutputStream";
    public static final String WARN_LEVEL_STRING = PREFIX + Names.WARN_LEVEL_STRING;
    public static final String LEVEL_IN_BRACKETS = PREFIX + Names.LEVEL_IN_BRACKETS;
    public static final String LOG_FILE = PREFIX + Names.LOG_FILE;
    public static final String SHOW_SHORT_LOG_NAME = PREFIX + Names.SHOW_SHORT_LOG_NAME;
    public static final String SHOW_LOG_NAME = PREFIX + Names.SHOW_LOG_NAME;
    public static final String SHOW_THREAD_NAME = PREFIX + Names.SHOW_THREAD_NAME;
    public static final String DATE_TIME_FORMAT = PREFIX + Names.DATE_TIME_FORMAT;
    public static final String SHOW_DATE_TIME = PREFIX + Names.SHOW_DATE_TIME;
    public static final String JSON_ENABLED = PREFIX + Names.JSON_ENABLED;
    public static final String DEFAULT_LOG_LEVEL = PREFIX + Names.DEFAULT_LOG_LEVEL;
    public static final String EMBED_EXCEPTION = PREFIX + Names.EMBED_EXCEPTION;

    // This is not available in SimpleLogger, but added here to simplify testing.
    static final String CONFIGURATION_FILE = PREFIX + Names.CONFIGURATION_FILE;
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
    public static final boolean JSON_ENABLED = false;
    public static final String DEFAULT_LOG_LEVEL = "INFO";
    public static final boolean EMBED_EXCEPTION = false;

    public static final String CONFIGURATION_FILE = "simplelogger.properties";
  }

  public abstract static class DTFormatter {
    public static DTFormatter create(String dateTimeFormat) {
      if (dateTimeFormat == null) {
        return new DiffDTFormatter();
      }
      try {
        return new NewDTFormatter(dateTimeFormat);
      } catch (Throwable t) {
        try {
          return new LegacyDTFormatter(dateTimeFormat);
        } catch (IllegalArgumentException e) {
        }
      }
      return new DiffDTFormatter();
    }

    public abstract void appendFormattedDate(
        StringBuilder builder, long timeMillis, long startTimeMillis);
  }

  public static class DiffDTFormatter extends DTFormatter {
    @Override
    public void appendFormattedDate(StringBuilder builder, long timeMillis, long startTimeMillis) {
      builder.append(timeMillis - startTimeMillis);
    }
  }

  public static class LegacyDTFormatter extends DTFormatter {
    private final DateFormat dateFormat;

    public LegacyDTFormatter(String dateTimeFormat) {
      this.dateFormat = new SimpleDateFormat(dateTimeFormat);
    }

    @Override
    public void appendFormattedDate(StringBuilder builder, long timeMillis, long startTimeMillis) {
      Date date = new Date(timeMillis);
      String dateString;
      synchronized (dateFormat) {
        dateString = dateFormat.format(date);
      }
      builder.append(dateString);
    }
  }

  public static class NewDTFormatter extends DTFormatter {
    private final Object dateTimeFormatter;
    private final MethodHandle formatTo;
    private final MethodHandle instantOfEpochMilli;
    private final Object zoneId;
    private final MethodHandle zdtOfInstant;

    public NewDTFormatter(String dateTimeFormat) {
      MethodHandles.Lookup l = MethodHandles.publicLookup();
      try {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        Class<?> fClass = cl.loadClass("java.time.format.DateTimeFormatter");
        Class<?> tClass = cl.loadClass("java.time.temporal.TemporalAccessor");
        Class<?> iClass = cl.loadClass("java.time.Instant");
        Class<?> zdtClass = cl.loadClass("java.time.ZonedDateTime");
        Class<?> zClass = cl.loadClass("java.time.ZoneId");
        this.dateTimeFormatter =
            l.findStatic(fClass, "ofPattern", MethodType.methodType(fClass, String.class))
                .invoke(dateTimeFormat);
        this.formatTo =
            l.findVirtual(
                fClass, "formatTo", MethodType.methodType(void.class, tClass, Appendable.class));
        this.instantOfEpochMilli =
            l.findStatic(iClass, "ofEpochMilli", MethodType.methodType(iClass, long.class));
        this.zoneId = l.findStatic(zClass, "systemDefault", MethodType.methodType(zClass)).invoke();
        this.zdtOfInstant =
            l.findStatic(zdtClass, "ofInstant", MethodType.methodType(zdtClass, iClass, zClass));
      } catch (Throwable t) {
        throw new IllegalArgumentException();
      }
    }

    @Override
    public void appendFormattedDate(StringBuilder builder, long timeMillis, long startTimeMillis) {
      try {
        formatTo.invoke(
            dateTimeFormatter,
            zdtOfInstant.invoke(instantOfEpochMilli.invoke(timeMillis), zoneId),
            builder);
      } catch (Throwable t) {
        // ignore
      }
    }
  }

  @SuppressForbidden
  static PrintStream getPrintStream(String logFile) {
    PrintStreamWrapper printStreamWrapper;
    switch (logFile.toLowerCase(Locale.ROOT)) {
      case "system.err":
        printStreamWrapper = new PrintStreamWrapper(System.err);
        break;
      case "system.out":
        printStreamWrapper = new PrintStreamWrapper(System.out);
        break;
      default:
        FileOutputStream outputStream = null;
        try {
          File outputFile = new File(logFile);
          File parentFile = outputFile.getParentFile();
          if (parentFile != null) {
            parentFile.mkdirs();
          }
          outputStream = new FileOutputStream(outputFile);
          PrintStream printStream = new PrintStream(outputStream, true);
          LogReporter.register(outputFile);
          return printStream;
        } catch (IOException | SecurityException e) {
          if (outputStream != null) {
            try {
              outputStream.close();
            } catch (IOException ce) {
              // TODO maybe have support for delayed logging of early failures?
            }
          }
          // TODO maybe have support for delayed logging of early failures?
          printStreamWrapper = new PrintStreamWrapper(System.err);
        }
    }
    LogReporter.register(printStreamWrapper);
    return printStreamWrapper;
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
    // Load properties in the same way that SimpleLogger does
    try (InputStream inputStream =
        AccessController.doPrivileged(new ResourceStreamPrivilegedAction(fileName))) {
      if (inputStream != null) {
        Properties properties = new Properties();
        properties.load(inputStream);
        return properties;
      }
    } catch (IOException e) {
      // ignored
    }

    return null;
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

  private final Properties properties;
  private final Properties fileProperties;

  // Package reachable for SLCompatHelper and tests
  final String warnLevelString;
  final boolean levelInBrackets;
  final PrintStream printStream;
  final boolean showShortLogName;
  final boolean showLogName;
  final boolean showThreadName;
  final DTFormatter dateTimeFormatter;
  final boolean showDateTime;
  final boolean jsonEnabled;
  final LogLevel defaultLogLevel;
  final boolean embedException;

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
        DTFormatter.create(
            getString(
                properties, fileProperties, Keys.DATE_TIME_FORMAT, Defaults.DATE_TIME_FORMAT)),
        getBoolean(properties, fileProperties, Keys.SHOW_DATE_TIME, Defaults.SHOW_DATE_TIME),
        getBoolean(properties, fileProperties, Keys.JSON_ENABLED, Defaults.JSON_ENABLED),
        LogLevel.fromString(
            getString(
                properties, fileProperties, Keys.DEFAULT_LOG_LEVEL, Defaults.DEFAULT_LOG_LEVEL)),
        getBoolean(properties, fileProperties, Keys.EMBED_EXCEPTION, Defaults.EMBED_EXCEPTION));
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
      DTFormatter dateTimeFormatter,
      boolean showDateTime,
      boolean jsonEnabled,
      LogLevel defaultLogLevel,
      boolean embedException) {
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
    this.jsonEnabled = jsonEnabled;
    this.defaultLogLevel = defaultLogLevel;
    this.embedException = embedException;
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

  public Map<String, Object> getSettingsDescription() {
    Map<String, Object> settingsDescription = new HashMap<>();
    settingsDescription.put(
        Names.WARN_LEVEL_STRING,
        warnLevelString != null ? warnLevelString : LogLevel.WARN.toString());
    settingsDescription.put(Names.LEVEL_IN_BRACKETS, levelInBrackets);
    settingsDescription.put(
        Names.LOG_FILE, getString(properties, fileProperties, Keys.LOG_FILE, Defaults.LOG_FILE));
    settingsDescription.put(Names.SHOW_LOG_NAME, showLogName);
    settingsDescription.put(Names.SHOW_SHORT_LOG_NAME, showShortLogName);
    settingsDescription.put(Names.SHOW_THREAD_NAME, showThreadName);
    settingsDescription.put(Names.SHOW_DATE_TIME, showDateTime);
    settingsDescription.put(Names.JSON_ENABLED, jsonEnabled);
    String dateTimeFormat =
        getString(properties, fileProperties, Keys.DATE_TIME_FORMAT, Defaults.DATE_TIME_FORMAT);
    settingsDescription.put(
        Names.DATE_TIME_FORMAT, dateTimeFormat != null ? dateTimeFormat : "relative");
    settingsDescription.put(Names.DEFAULT_LOG_LEVEL, defaultLogLevel.toString());
    settingsDescription.put(Names.EMBED_EXCEPTION, embedException);
    settingsDescription.put(
        Names.CONFIGURATION_FILE,
        properties.getProperty(Keys.CONFIGURATION_FILE, Defaults.CONFIGURATION_FILE));

    return settingsDescription;
  }
}
