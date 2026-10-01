package datadog.trace.observer.bootstrap;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
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

/** Private stock source views and process transport; no subject configuration is imported. */
public final class ObserverRuntime {
  public static final String PROPERTY_PREFIX = "tracing.observer.config.";
  public static final String ENV_PREFIX = "TRACING_OBSERVER_CONFIG_";
  private static final String CHILD = "tracing.observer.child.v1";
  private static final int LIMIT = 1024 * 1024;
  private static final Set<String> ALIASES = metadata("observer-config-aliases.txt");
  private static final Set<String> SENSITIVE = metadata("observer-sensitive-config.txt");
  private static final String GENERATED = "tracing.observer.generated.";
  private static final Properties CONFIG = new Properties();
  private static final Map<String, String> ENVIRONMENT = new HashMap<>();
  private static final Map<String, String> CARRIER = new HashMap<>();
  private static final Map<String, String> LAUNCH = new HashMap<>();

  /** Test frameworks that only run as fixtures inside the observed JUnit Platform tests. */
  private static final String[] NESTED_ONLY_INTEGRATIONS = {
    "junit-4", "testng", "karate", "scalatest", "weaver", "cucumber"
  };

  private static volatile boolean initialized;
  private static String generatedLogFile;
  private static String configurationFingerprint;

  /** Initialize the private source view before the stock bootstrap reads configuration. */
  public static synchronized void initializePremain(String arguments) {
    initialize(arguments);
  }

  /** Private source initialization, also usable by standalone offline configuration tests. */
  public static synchronized void initialize(String arguments) {
    if (initialized) {
      throw new IllegalStateException("Observer runtime already initialized");
    }
    String child = System.getProperty(CHILD);
    boolean envelope = arguments != null && arguments.startsWith("v1:");
    if (envelope && child != null) {
      throw new IllegalArgumentException("Conflicting observer launch channels");
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
    Properties childOverrides = new Properties();
    if (child != null) {
      childOverrides.putAll(CONFIG);
    }
    if (child != null || envelope) {
      List<Map<String, String>> maps = decode(child != null ? child : arguments.substring(3));
      if (child != null) {
        CONFIG.clear();
        CONFIG.putAll(maps.get(0));
      } else {
        for (Map.Entry<String, String> e : maps.get(0).entrySet()) {
          CONFIG.setProperty(privateSpelling(e.getKey()), e.getValue());
        }
      }
      ENVIRONMENT.putAll(maps.get(1));
      LAUNCH.clear();
      LAUNCH.putAll(maps.get(2));
      CARRIER.putAll(maps.get(3));
    }
    configurationFingerprint = fingerprint();
    CONFIG.putAll(CARRIER);
    CONFIG.putAll(childOverrides);
    disableNestedOnlyIntegrations();
    configureFileLogger();
    initialized = true;
  }

  /** Off unless the caller configured them: observing their fixtures would double-report. */
  private static void disableNestedOnlyIntegrations() {
    for (String name : NESTED_ONLY_INTEGRATIONS) {
      String env = name.toUpperCase(Locale.ROOT).replace('-', '_');
      if (!CONFIG.containsKey("dd.integration." + name + ".enabled")
          && !CONFIG.containsKey("dd.trace.integration." + name + ".enabled")
          && !ENVIRONMENT.containsKey("DD_INTEGRATION_" + env + "_ENABLED")
          && !ENVIRONMENT.containsKey("DD_TRACE_INTEGRATION_" + env + "_ENABLED")) {
        CONFIG.setProperty("dd.integration." + name + ".enabled", "false");
      }
    }
  }

  private ObserverRuntime() {}

  private static void ensureInitialized() {
    if (!initialized) {
      synchronized (ObserverRuntime.class) {
        if (!initialized) {
          initialize(null);
        }
      }
    }
  }

  /** Stable task input for the captured caller configuration, not later mutable IPC state. */
  public static String configurationFingerprint() {
    ensureInitialized();
    return configurationFingerprint;
  }

  private static String fingerprint() {
    Map<String, String> properties = new HashMap<>();
    for (String key : CONFIG.stringPropertyNames()) {
      properties.put(key, CONFIG.getProperty(key));
    }
    String encoded = encode(asList(properties, ENVIRONMENT, LAUNCH, CARRIER));
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean usingGeneratedFileLogger() {
    ensureInitialized();
    return generatedLogFile != null
        && generatedLogFile.equals(
            CONFIG.getProperty("datadog.trace.observer.slf4j.simpleLogger.logFile"));
  }

  private static void configureFileLogger() {
    String logs = LAUNCH.get("tracing.observer.log.directory");
    if (logs != null) {
      String key = "datadog.trace.observer.slf4j.simpleLogger.logFile";
      if (!CONFIG.containsKey(key)) {
        generatedLogFile =
            new File(logs, "observer-" + Long.toHexString(System.nanoTime()) + ".log").getPath();
        CONFIG.setProperty(key, generatedLogFile);
      }
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
    // Internal snapshots already use private keys; never relocate them recursively.
    if (key.startsWith("datadog.trace.observer.")) {
      return key;
    }
    if (key.startsWith("datadog.") || key.startsWith("net.bytebuddy.")) {
      // Per-class logger keys also contain the target class name in their suffix.
      return key.replace("com.datadog.", "__OBSERVER_COM_DOT__")
          .replace("datadog.", "datadog.trace.observer.")
          .replace("__OBSERVER_COM_DOT__", "datadog.trace.observer.com.datadog.")
          .replace("net.bytebuddy.", "datadog.trace.observer.net.bytebuddy.");
    }
    return key;
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
        for (String key : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
          result.add(key);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load observer configuration metadata", e);
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
    try {
      return Boolean.parseBoolean(getProperty(key));
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return false;
    }
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
    Map<String, String> properties = new HashMap<>();
    for (String key : CONFIG.stringPropertyNames()) {
      properties.put(key, CONFIG.getProperty(key));
    }
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
      properties.remove("datadog.trace.observer.slf4j.simpleLogger.logFile");
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
    Map<String, String> none = emptyMap();
    result.add("-D" + CHILD + "=" + encode(asList(none, none, LAUNCH, carrier)));
    return result;
  }

  static String encode(List<Map<String, String>> maps) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(1);
      for (Map<String, String> map : maps) {
        Map<String, String> sorted = new TreeMap<>(map);
        sorted.values().removeIf(value -> value == null);
        out.writeInt(sorted.size());
        for (Map.Entry<String, String> e : sorted.entrySet()) {
          writeString(out, e.getKey());
          writeString(out, e.getValue());
        }
      }
      if (bytes.size() > LIMIT) {
        throw new IllegalArgumentException("Observer envelope too large");
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void writeString(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  static List<Map<String, String>> decode(String encoded) {
    if (encoded.length() > LIMIT * 2) {
      throw new IllegalArgumentException("Observer envelope too large");
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(encoded);
      if (decoded.length > LIMIT) {
        throw new IllegalArgumentException("Observer envelope too large");
      }
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(decoded));
      if (in.readInt() != 1) {
        throw new IOException("version");
      }
      List<Map<String, String>> maps = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        int count = in.readInt();
        if (count < 0 || count > 10000) {
          throw new IOException("count");
        }
        Map<String, String> map = new HashMap<>();
        for (int j = 0; j < count; j++) {
          if (map.put(readString(in), readString(in)) != null) {
            throw new IOException("duplicate key");
          }
        }
        maps.add(map);
      }
      if (in.available() != 0) {
        throw new IOException("trailing data");
      }
      return maps;
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Malformed observer child envelope", e);
    }
  }

  private static String readString(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > LIMIT || size > in.available()) {
      throw new IOException("length");
    }
    byte[] bytes = new byte[size];
    in.readFully(bytes);
    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
  }

  public static boolean isOuterGradleExecution() {
    return isOuterGradleExecution(Thread.currentThread().getStackTrace());
  }

  /** Fail closed: only the synchronous Gradle-owned outer engine launch is supported. */
  public static boolean isOuterGradleExecution(StackTraceElement[] stack) {
    boolean gradle = false;
    int engines = 0;
    for (StackTraceElement frame : stack) {
      if (frame
              .getClassName()
              .equals("org.junit.platform.launcher.core.EngineExecutionOrchestrator")
          && frame.getMethodName().equals("executeEngine")) {
        engines++;
      }
      if (frame
              .getClassName()
              .equals(
                  "org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestDefinitionProcessor$CollectThenExecuteTestDefinitionConsumer")
          && frame.getMethodName().equals("processAllTestDefinitions")) {
        gradle = true;
      }
    }
    return gradle && engines == 1;
  }
}
