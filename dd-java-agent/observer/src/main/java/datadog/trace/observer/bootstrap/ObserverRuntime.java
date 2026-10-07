package datadog.trace.observer.bootstrap;

import static java.util.Collections.unmodifiableMap;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Private stock source views and process transport; no subject configuration is imported. */
@SuppressForbidden
public final class ObserverRuntime {
  public static final String MANIFEST_ATTRIBUTE = "Tracing-The-Tracer-Observer";
  public static final String MANIFEST_VERSION = "3";
  public static final String ROOTS_RESOURCE = "observer-relocated-roots.txt";
  public static final String ALIASES_RESOURCE = "observer-config-aliases.txt";
  public static final String SENSITIVE_RESOURCE = "observer-sensitive-config.txt";
  public static final String PROPERTY_PREFIX = "tracing.observer.config.";
  public static final String ENV_PREFIX = "TRACING_OBSERVER_CONFIG_";
  static final String CHILD = "tracing.observer.child.v2";
  private static final String GENERATED = "tracing.observer.generated.";
  private static final String LOG_FILE = "datadog.trace.observer.slf4j.simpleLogger.logFile";
  private static final String LOG_DIRECTORY = "tracing.observer.log.directory";

  /** Test frameworks that only run as fixtures inside the observed JUnit Platform tests. */
  private static final String[] NESTED_ONLY_INTEGRATIONS = {
    "junit-4", "testng", "karate", "scalatest", "weaver", "cucumber"
  };

  private static final Set<String> ROOTS = metadata(ROOTS_RESOURCE);
  private static final Set<String> ALIASES = metadata(ALIASES_RESOURCE);
  private static final Set<String> SENSITIVE = metadata(SENSITIVE_RESOURCE);
  private static final Properties CONFIG = new Properties();
  private static final Map<String, String> ENVIRONMENT = new HashMap<>();
  private static final Map<String, String> CARRIER = new HashMap<>();
  private static final Map<String, String> LAUNCH = new HashMap<>();

  private static final AtomicInteger OUTER_ENGINES = new AtomicInteger();
  private static final AtomicBoolean UNOWNED_ENGINE_WARNING = new AtomicBoolean();

  private static final Object LOCK = new Object();
  private static volatile boolean initialized;
  private static Envelope callerConfiguration;
  private static String generatedLogFile;

  private ObserverRuntime() {}

  /** Initialize the private source view before the stock bootstrap reads configuration. */
  public static void initializePremain() {
    synchronized (LOCK) {
      initialize();
    }
  }

  private static void initialize() {
    if (initialized) {
      throw new IllegalStateException("Observer runtime already initialized");
    }
    for (String key : System.getProperties().stringPropertyNames()) {
      if (key.startsWith(PROPERTY_PREFIX)) {
        CONFIG.setProperty(
            privateSpelling(key.substring(PROPERTY_PREFIX.length())), System.getProperty(key));
      } else if (key.startsWith("tracing.observer.") && !key.equals(CHILD)) {
        LAUNCH.put(key, System.getProperty(key));
      }
    }
    for (Map.Entry<String, String> e : System.getenv().entrySet()) {
      if (e.getKey().startsWith(ENV_PREFIX)) {
        ENVIRONMENT.put(e.getKey().substring(ENV_PREFIX.length()), e.getValue());
      }
    }
    // Gradle applies -D daemon arguments after premain, so the environment is the early channel.
    String logs = System.getenv("TRACING_OBSERVER_LOG_DIRECTORY");
    if (logs != null) {
      LAUNCH.putIfAbsent(LOG_DIRECTORY, logs);
    }
    String child = System.getProperty(CHILD);
    Properties workerOverrides = new Properties();
    if (child != null) {
      // Explicit worker JVM properties still win over the parent's generated settings.
      workerOverrides.putAll(CONFIG);
      Envelope parent = Envelope.decode(child);
      CONFIG.clear();
      CONFIG.putAll(parent.config);
      ENVIRONMENT.putAll(parent.environment);
      LAUNCH.clear();
      LAUNCH.putAll(parent.launch);
      CARRIER.putAll(parent.carrier);
    }
    callerConfiguration = new Envelope(toMap(CONFIG), ENVIRONMENT, LAUNCH, CARRIER);
    CONFIG.putAll(CARRIER);
    CONFIG.putAll(workerOverrides);
    configureFileLogger();
    initialized = true;
  }

  private static void ensureInitialized() {
    if (!initialized) {
      synchronized (LOCK) {
        if (!initialized) {
          initialize();
        }
      }
    }
  }

  /** Stable task input for the captured caller configuration, not later mutable IPC state. */
  public static String configurationFingerprint() {
    ensureInitialized();
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(callerConfiguration.encode().getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Lowest-precedence stock source, so any explicit observer setting overrides these defaults. */
  public static Map<String, Object> stableConfig(String origin) {
    Map<String, Object> config = new HashMap<>();
    if (origin.equals("LOCAL_STABLE_CONFIG")) {
      for (String name : NESTED_ONLY_INTEGRATIONS) {
        config.put(
            "DD_TRACE_" + name.toUpperCase(Locale.ROOT).replace('-', '_') + "_ENABLED", "false");
      }
      // This repository has too many root packages to infer them, which would cover everything,
      // including JDK and test-only classes. Cover the code under test instead.
      config.put("DD_CIVISIBILITY_CODE_COVERAGE_INCLUDES", "datadog.*:com.datadog.*");
    }
    return config;
  }

  /**
   * Moves every package reference whose {@code datadog.<root>}, {@code com.datadog.<root>} or
   * {@code net.bytebuddy} root is listed into the observer namespace. Anything else, such as host
   * paths, wire names or JNDI names, keeps its stock spelling.
   */
  public static String relocate(String value, Set<String> roots) {
    StringBuilder result = null;
    int copied = 0;
    for (int i = value.indexOf("datadog"); i >= 0; i = value.indexOf("datadog", i + 1)) {
      int start = i;
      int prefixEnd = i + "datadog".length();
      if (prefixEnd >= value.length()) {
        break;
      }
      char separator = value.charAt(prefixEnd);
      if (separator != '.' && separator != '/') {
        continue;
      }
      if (i >= 4 && value.startsWith("com", i - 4) && value.charAt(i - 1) == separator) {
        start = i - 4;
      }
      if (!startsName(value, start)
          || relocated(value, start, separator)
          || value.startsWith("trace" + separator + "observer" + separator, prefixEnd + 1)) {
        continue;
      }
      int segmentEnd = prefixEnd + 1;
      while (segmentEnd < value.length()
          && Character.isJavaIdentifierPart(value.charAt(segmentEnd))) {
        segmentEnd++;
      }
      String root =
          (start == i ? "datadog/" : "com/datadog/") + value.substring(prefixEnd + 1, segmentEnd);
      if (!roots.contains(root)) {
        continue;
      }
      if (result == null) {
        result = new StringBuilder(value.length() + 32);
      }
      result.append(value, copied, start).append("datadog").append(separator);
      result.append("trace").append(separator).append("observer").append(separator);
      copied = start == i ? prefixEnd + 1 : start;
    }
    String bytebuddy =
        result == null ? value : result.append(value, copied, value.length()).toString();
    if (roots.contains("net/bytebuddy")) {
      bytebuddy = relocateByteBuddy(bytebuddy, '.');
      bytebuddy = relocateByteBuddy(bytebuddy, '/');
    }
    return bytebuddy;
  }

  private static String relocateByteBuddy(String value, char separator) {
    String name = "net" + separator + "bytebuddy" + separator;
    StringBuilder result = null;
    int copied = 0;
    for (int i = value.indexOf(name); i >= 0; i = value.indexOf(name, i + 1)) {
      if (!startsName(value, i) || relocated(value, i, separator)) {
        continue;
      }
      if (result == null) {
        result = new StringBuilder(value.length() + 32);
      }
      result.append(value, copied, i).append("datadog").append(separator);
      result.append("trace").append(separator).append("observer").append(separator);
      copied = i;
    }
    return result == null ? value : result.append(value, copied, value.length()).toString();
  }

  /** Whether the name at {@code index} already sits under the observer namespace. */
  private static boolean relocated(String value, int index, char separator) {
    String observer = "datadog" + separator + "trace" + separator + "observer" + separator;
    return index >= observer.length() && value.startsWith(observer, index - observer.length());
  }

  /**
   * Package names here are lower case, so a name starts anywhere except after a lower-case letter,
   * digit or underscore. That covers descriptors such as {@code ILdatadog/} and {@code -Dnet.}.
   */
  private static boolean startsName(String value, int index) {
    if (index == 0) {
      return true;
    }
    char previous = value.charAt(index - 1);
    return !(previous >= 'a' && previous <= 'z')
        && !(previous >= '0' && previous <= '9')
        && previous != '_';
  }

  public static boolean usingGeneratedFileLogger() {
    ensureInitialized();
    return generatedLogFile != null && generatedLogFile.equals(CONFIG.getProperty(LOG_FILE));
  }

  private static void configureFileLogger() {
    String logs = LAUNCH.get(LOG_DIRECTORY);
    if (logs != null && !CONFIG.containsKey(LOG_FILE)) {
      generatedLogFile =
          new File(logs, "observer-" + Long.toHexString(System.nanoTime()) + ".log").getPath();
      CONFIG.setProperty(LOG_FILE, generatedLogFile);
    }
  }

  /** Resource files keep ordinary logger key spellings; values are never relocated. */
  public static Properties loggerProperties(Properties properties) {
    if (properties == null) {
      return null;
    }
    Properties result = new Properties();
    for (String key : properties.stringPropertyNames()) {
      result.setProperty(privateSpelling(key), properties.getProperty(key));
    }
    return result;
  }

  private static String privateSpelling(String key) {
    return key.startsWith("datadog.") || key.startsWith("net.bytebuddy.")
        ? relocate(key, ROOTS)
        : key;
  }

  private static Set<String> metadata(String resource) {
    Set<String> result = new HashSet<>();
    try (InputStream in = ObserverRuntime.class.getResourceAsStream("/" + resource)) {
      if (in != null) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
          out.write(buffer, 0, n);
        }
        for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
          if (!line.isEmpty()) {
            result.add(line);
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load observer metadata " + resource, e);
    }
    return result;
  }

  private static boolean privateKey(String key) {
    return key.startsWith("dd.")
        || key.startsWith("datadog.")
        || key.startsWith("otel.")
        || key.startsWith("net.bytebuddy.")
        || key.startsWith("tracing.observer.")
        || CONFIG.containsKey(key)
        || ALIASES.contains(key);
  }

  public static String getProperty(String key) {
    return getProperty(key, null);
  }

  public static String getProperty(String key, String fallback) {
    ensureInitialized();
    if (key == null) {
      throw new NullPointerException("key");
    }
    if (key.isEmpty()) {
      throw new IllegalArgumentException("key is empty");
    }
    if (!privateKey(key)) {
      return System.getProperty(key, fallback);
    }
    String value = CONFIG.getProperty(key);
    return value == null ? fallback : value;
  }

  public static boolean getBoolean(String key) {
    return key != null && !key.isEmpty() && Boolean.parseBoolean(getProperty(key));
  }

  public static String setProperty(String key, String value) {
    getProperty(key);
    return privateKey(key)
        ? (String) CONFIG.setProperty(key, value)
        : System.setProperty(key, value);
  }

  public static String clearProperty(String key) {
    getProperty(key);
    return privateKey(key) ? (String) CONFIG.remove(key) : System.clearProperty(key);
  }

  public static Properties getProperties() {
    ensureInitialized();
    Properties result = new Properties();
    Properties real = System.getProperties();
    for (String key : real.stringPropertyNames()) {
      if (!privateKey(key)) {
        result.setProperty(key, real.getProperty(key));
      }
    }
    result.putAll(CONFIG);
    return result;
  }

  public static String getenv(String key) {
    ensureInitialized();
    if (key == null) {
      throw new NullPointerException("key");
    }
    return key.startsWith("DD_")
            || key.startsWith("OTEL_")
            || key.startsWith("TRACING_OBSERVER_")
            || ALIASES.contains(key)
            || ENVIRONMENT.containsKey(key)
        ? ENVIRONMENT.get(key)
        : System.getenv(key);
  }

  public static Map<String, String> getenv() {
    ensureInitialized();
    Map<String, String> result = new HashMap<>();
    for (String key : System.getenv().keySet()) {
      String value = getenv(key);
      if (value != null) {
        result.put(key, value);
      }
    }
    result.putAll(ENVIRONMENT);
    return unmodifiableMap(result);
  }

  /**
   * Only the parent-generated map can classify a worker; ambient HTTP headers are not a carrier.
   */
  public static Map<String, String> propagationProperties() {
    ensureInitialized();
    return unmodifiableMap(CARRIER);
  }

  /** Mark only generated settings; stock debug/additional arguments remain untouched. */
  public static Map<String, String> generatedProperties(Map<String, String> generated) {
    ensureInitialized();
    Map<String, String> properties = toMap(CONFIG);
    for (Map.Entry<String, String> entry : generated.entrySet()) {
      String environmentKey =
          entry.getKey().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
      // A resolved environment/file credential must never be promoted into a JVM argument.
      // Explicit caller JVM properties keep their original source role.
      if (!SENSITIVE.contains(environmentKey) || CONFIG.containsKey(entry.getKey())) {
        properties.put(entry.getKey(), entry.getValue());
      }
    }
    if (generatedLogFile != null) {
      properties.remove(LOG_FILE);
    }
    Map<String, String> marked = new HashMap<>();
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      if (entry.getValue() != null) {
        marked.put(GENERATED + entry.getKey(), entry.getValue());
      }
    }
    return marked;
  }

  /** Encode after stock Gradle project-property substitution, preserving non-generated args. */
  public static Iterable<String> childArguments(Iterable<String> arguments) {
    ensureInitialized();
    Map<String, String> carrier = new HashMap<>();
    List<String> result = new ArrayList<>();
    for (String argument : arguments) {
      if (argument.startsWith("-D" + GENERATED)) {
        int separator = argument.indexOf('=');
        carrier.put(
            argument.substring(2 + GENERATED.length(), separator),
            argument.substring(separator + 1));
      } else {
        result.add(argument);
      }
    }
    // Environment is inherited in its normal namespaced role, never Base64-encoded in argv.
    Map<String, String> none = new HashMap<>();
    result.add("-D" + CHILD + "=" + new Envelope(none, none, LAUNCH, carrier).encode());
    return result;
  }

  public static boolean isOuterGradleExecution() {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    if (isOuterGradleExecution(stack)) {
      OUTER_ENGINES.incrementAndGet();
      return true;
    }
    if (countEngines(stack) == 1 && UNOWNED_ENGINE_WARNING.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(new Thread(ObserverRuntime::warnIfNoOuterEngine));
    }
    return false;
  }

  private static void warnIfNoOuterEngine() {
    if (OUTER_ENGINES.get() == 0) {
      System.err.println(
          "[dd.trace.observer] WARN - A JUnit Platform engine ran without the Gradle test worker"
              + " frame the observer expects, and no outer engine was observed. No tests were"
              + " reported from this JVM. Check whether Gradle's JUnit Platform test processor"
              + " was renamed.");
    }
  }

  /**
   * Fail closed: only the synchronous Gradle-owned outer engine launch is supported. A nested
   * launch on another thread also has a single engine frame, but no Gradle frame.
   */
  public static boolean isOuterGradleExecution(StackTraceElement[] stack) {
    boolean gradle = false;
    for (StackTraceElement frame : stack) {
      if (frame
              .getClassName()
              .equals(
                  "org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestDefinitionProcessor$CollectThenExecuteTestDefinitionConsumer")
          && frame.getMethodName().equals("processAllTestDefinitions")) {
        gradle = true;
      }
    }
    return gradle && countEngines(stack) == 1;
  }

  private static int countEngines(StackTraceElement[] stack) {
    int engines = 0;
    for (StackTraceElement frame : stack) {
      if (frame
              .getClassName()
              .equals("org.junit.platform.launcher.core.EngineExecutionOrchestrator")
          && frame.getMethodName().equals("executeEngine")) {
        engines++;
      }
    }
    return engines;
  }

  private static Map<String, String> toMap(Properties properties) {
    Map<String, String> result = new HashMap<>();
    for (String key : properties.stringPropertyNames()) {
      result.put(key, properties.getProperty(key));
    }
    return result;
  }

  /** Settings a parent hands to a worker. Not encrypted: never put secrets in it. */
  static final class Envelope {
    private static final int LIMIT = 1024 * 1024;
    private static final String[] SECTIONS = {"config", "environment", "launch", "carrier"};

    final Map<String, String> config;
    final Map<String, String> environment;
    final Map<String, String> launch;
    final Map<String, String> carrier;

    Envelope(
        Map<String, String> config,
        Map<String, String> environment,
        Map<String, String> launch,
        Map<String, String> carrier) {
      this.config = new TreeMap<>(config);
      this.environment = new TreeMap<>(environment);
      this.launch = new TreeMap<>(launch);
      this.carrier = new TreeMap<>(carrier);
    }

    /** Sorted, NUL-separated section, key and value triples, so the encoding is deterministic. */
    String encode() {
      StringBuilder text = new StringBuilder();
      List<Map<String, String>> maps = sections();
      for (int i = 0; i < SECTIONS.length; i++) {
        for (Map.Entry<String, String> e : maps.get(i).entrySet()) {
          if (e.getValue() == null) {
            continue;
          }
          if (e.getKey().indexOf('\0') >= 0 || e.getValue().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Observer envelope entries cannot contain NUL");
          }
          text.append(SECTIONS[i]).append('\0');
          text.append(e.getKey()).append('\0').append(e.getValue()).append('\0');
        }
      }
      byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
      if (bytes.length > LIMIT) {
        throw new IllegalArgumentException("Observer envelope too large");
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static Envelope decode(String encoded) {
      try {
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        if (bytes.length > LIMIT) {
          throw new IllegalArgumentException("too large");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        Envelope envelope =
            new Envelope(
                new HashMap<String, String>(),
                new HashMap<String, String>(),
                new HashMap<String, String>(),
                new HashMap<String, String>());
        List<Map<String, String>> maps = envelope.sections();
        int start = 0;
        while (start < text.length()) {
          int section = text.indexOf('\0', start);
          int key = section < 0 ? -1 : text.indexOf('\0', section + 1);
          int value = key < 0 ? -1 : text.indexOf('\0', key + 1);
          if (value < 0) {
            throw new IllegalArgumentException("truncated entry");
          }
          int index = sectionIndex(text.substring(start, section));
          if (maps.get(index).put(text.substring(section + 1, key), text.substring(key + 1, value))
              != null) {
            throw new IllegalArgumentException("duplicate key");
          }
          start = value + 1;
        }
        return envelope;
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Malformed observer child envelope", e);
      }
    }

    private static int sectionIndex(String name) {
      for (int i = 0; i < SECTIONS.length; i++) {
        if (SECTIONS[i].equals(name)) {
          return i;
        }
      }
      throw new IllegalArgumentException("unknown section " + name);
    }

    private List<Map<String, String>> sections() {
      List<Map<String, String>> maps = new ArrayList<>();
      maps.add(config);
      maps.add(environment);
      maps.add(launch);
      maps.add(carrier);
      return maps;
    }
  }
}
