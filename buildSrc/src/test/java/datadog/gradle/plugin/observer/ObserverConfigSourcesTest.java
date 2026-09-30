package datadog.gradle.plugin.observer;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Differential source tests without installing either tracer or reading host stable files. */
@EnabledIfSystemProperty(named = "observer.test.artifact", matches = ".+")
class ObserverConfigSourcesTest {
  private static final String STOCK = "datadog.trace.";
  private static final String OBSERVER = "datadog.trace.observer.trace.";

  /** Only test environment transport is substituted, below the stock config helper. */
  public static class Environment {
    public static Map<String, String> values = new HashMap<>();

    public static String getenv(String key) {
      return values.get(key);
    }

    public static Map<String, String> getenv() {
      return new HashMap<>(values);
    }
  }

  @Test
  void defaultLoggerResourceIsPrivateButExplicitSelectionUsesOrdinaryKeys(@TempDir Path directory)
      throws Exception {
    Files.write(
        directory.resolve("simplelogger.properties"),
        "datadog.slf4j.simpleLogger.defaultLogLevel=ERROR\n".getBytes("ISO-8859-1"));
    Properties original = (Properties) System.getProperties().clone();
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader resources =
        new URLClassLoader(new URL[] {directory.toUri().toURL()}, null)) {
      Thread.currentThread().setContextClassLoader(resources);
      for (boolean explicit : new boolean[] {false, true}) {
        String key = "tracing.observer.config.datadog.slf4j.simpleLogger.configurationFile";
        if (explicit) {
          System.setProperty(key, "simplelogger.properties");
        } else {
          System.clearProperty(key);
        }
        try (SourceLoader loader = loader(true)) {
          Class<?> runtime = loader.loadClass("datadog.trace.observer.bootstrap.ObserverRuntime");
          Properties properties = (Properties) runtime.getMethod("getProperties").invoke(null);
          Class<?> settings = loader.loadClass(OBSERVER + "logging.simplelogger.SLCompatSettings");
          Object instance = settings.getConstructor(Properties.class).newInstance(properties);
          Map<?, ?> description =
              (Map<?, ?>) settings.getMethod("getSettingsDescription").invoke(instance);
          assertEquals(explicit ? "ERROR" : "INFO", description.get("defaultLogLevel"));
          assertEquals(
              explicit ? "simplelogger.properties" : "observer-simplelogger.properties",
              description.get("configurationFile"));
        }
      }
    } finally {
      System.setProperties(original);
      Thread.currentThread().setContextClassLoader(context);
    }
  }

  @Test
  void stockFactoriesAndPrivateSourcesAgree(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("observer.properties");
    Files.write(
        file,
        ("dd.service=file-service\ndd.trace.sample.rate=0.25\ndd.tags=file:yes,shared:file\ndd.api-key=file-dummy\n")
            .getBytes("ISO-8859-1"));
    for (boolean withFile : new boolean[] {false, true}) {
      for (String factory :
          new String[] {"createDefault", "withoutCollector", "withPropertiesOverride"}) {
        Map<String, Object> stock = evaluate(false, factory, withFile ? file : null);
        Map<String, Object> observer = evaluate(true, factory, withFile ? file : null);
        assertEquals(stock, observer, factory + " file=" + withFile);
        assertEquals(
            factory.equals("withPropertiesOverride") && withFile ? "provided" : "high-alias",
            observer.get("service"));
        assertEquals(71, observer.get("numeric"));
        assertEquals(false, observer.get("boolean"));
        assertEquals("env-dummy", observer.get("key"));
        assertEquals(!factory.equals("withoutCollector"), observer.get("collect"));
        @SuppressWarnings("unchecked")
        Map<String, String> tags = (Map<String, String>) observer.get("tags");
        assertEquals("property", tags.get("shared"));
        assertEquals("yes", tags.get("env"));
        if (withFile) {
          assertEquals("yes", tags.get("file"));
        }
      }
    }
  }

  @Test
  void propertyCredentialsRemainExcludedAndExplicitFilesAreReread(@TempDir Path directory)
      throws Exception {
    Path file = directory.resolve("observer.properties");
    Files.write(file, "dd.api-key=file-dummy\n".getBytes("ISO-8859-1"));
    Properties original = (Properties) System.getProperties().clone();
    try (SourceLoader loader = loader(true)) {
      System.setProperty("tracing.observer.config.dd.api-key", "ignored-property-dummy");
      System.setProperty("dd.trace.config", "/subject-file-must-not-be-read");
      Class<?> provider = loader.loadClass(OBSERVER + "bootstrap.config.provider.ConfigProvider");
      Object first = provider.getMethod("createDefault").invoke(null);
      Class<?> system =
          loader.loadClass(OBSERVER + "bootstrap.config.provider.SystemPropertiesConfigSource");
      assertNull(
          provider
              .getMethod(
                  "getStringExcludingSource",
                  String.class,
                  String.class,
                  Class.class,
                  String[].class)
              .invoke(first, "api-key", null, system, new String[0]));
      Class<?> runtime = loader.loadClass("datadog.trace.observer.bootstrap.ObserverRuntime");
      runtime
          .getMethod("setProperty", String.class, String.class)
          .invoke(null, "dd.trace.config", file.toString());
      Object second = provider.getMethod("createDefault").invoke(null);
      assertEquals(
          "file-dummy",
          provider
              .getMethod(
                  "getStringExcludingSource",
                  String.class,
                  String.class,
                  Class.class,
                  String[].class)
              .invoke(second, "api-key", null, system, new String[0]));
      Files.write(file, "dd.api-key=file-dummy-2\n".getBytes("ISO-8859-1"));
      Object third = provider.getMethod("createDefault").invoke(null);
      assertEquals(
          "file-dummy-2",
          provider
              .getMethod(
                  "getStringExcludingSource",
                  String.class,
                  String.class,
                  Class.class,
                  String[].class)
              .invoke(third, "api-key", null, system, new String[0]));
      assertEquals("/subject-file-must-not-be-read", System.getProperty("dd.trace.config"));
      Class<?> stable = loader.loadClass(OBSERVER + "bootstrap.config.provider.StableConfigSource");
      assertEquals(
          java.util.Collections.emptySet(),
          stable.getMethod("getKeys").invoke(stable.getField("LOCAL").get(null)));
      assertEquals(
          java.util.Collections.emptySet(),
          stable.getMethod("getKeys").invoke(stable.getField("FLEET").get(null)));
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void ordinaryDestinationsProductsRoleControlsAndFilesAreNotRestricted(@TempDir Path directory)
      throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    Path key = directory.resolve("owned-dummy.key");
    Files.write(key, "ordinary-offline-dummy".getBytes("UTF-8"));
    Map<String, String> values = new LinkedHashMap<>();
    values.put("site", "datad0g.com");
    values.put("civisibility.agentless.url", "https://example.invalid/tests");
    values.put("trace.agent.url", "https://example.invalid/agent");
    values.put("api-key-file", key.toString());
    values.put("application-key-file", key.toString());
    values.put("profiling.apikey.file", key.toString());
    values.put("profiling.enabled", "true");
    values.put("civisibility.auto.configuration.enabled", "false");
    values.put("civisibility.build.instrumentation.enabled", "false");
    values.put("writer.type", "DDAgentWriter");
    try {
      for (String source : new String[] {"property", "environment", "file", "override"}) {
        System.setProperties((Properties) original.clone());
        Properties fileValues = new Properties();
        Map<String, String> environment = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
          String full = "dd." + entry.getKey();
          if (source.equals("property")) {
            System.setProperty("tracing.observer.config." + full, entry.getValue());
          } else if (source.equals("environment")) {
            environment.put(
                "TRACING_OBSERVER_CONFIG_"
                    + full.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'),
                entry.getValue());
          } else {
            fileValues.setProperty(source.equals("file") ? full : entry.getKey(), entry.getValue());
          }
        }
        Path config = directory.resolve("ordinary.properties");
        if (source.equals("file")) {
          try (java.io.OutputStream out = Files.newOutputStream(config)) {
            fileValues.store(out, "offline owned inputs");
          }
          System.setProperty("tracing.observer.config.dd.trace.config", config.toString());
        }
        try (SourceLoader loader = loader(true)) {
          loader.loadClass(Environment.class.getName()).getField("values").set(null, environment);
          Class<?> provider =
              loader.loadClass(OBSERVER + "bootstrap.config.provider.ConfigProvider");
          Object instance =
              source.equals("override")
                  ? provider
                      .getMethod("withPropertiesOverride", Properties.class)
                      .invoke(null, fileValues)
                  : provider.getMethod("createDefault").invoke(null);
          for (Map.Entry<String, String> entry : values.entrySet()) {
            assertEquals(
                entry.getValue(),
                configString(provider, instance, entry.getKey()),
                source + " " + entry.getKey());
          }
        }
      }
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void childTransportKeepsCarrierAndInheritedEnvironmentWithoutSerializingCredentials(
      @TempDir Path directory) throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    Path file = directory.resolve("roundtrip.properties");
    Files.write(file, "dd.service=file-parent\n".getBytes("ISO-8859-1"));
    Map<String, String> environment = new HashMap<>();
    environment.put("TRACING_OBSERVER_CONFIG_DD_ENV", "private-environment");
    environment.put("TRACING_OBSERVER_CONFIG_DD_API_KEY", "owned-secret-marker");
    try (SourceLoader parent = loader(true)) {
      System.clearProperty("tracing.observer.child.v1");
      System.setProperty("tracing.observer.config.dd.trace.config", file.toString());
      parent.loadClass(Environment.class.getName()).getField("values").set(null, environment);
      Class<?> runtime = parent.loadClass("datadog.trace.observer.bootstrap.ObserverRuntime");
      initializeParent(runtime);
      Map<String, String> generated = new HashMap<>();
      generated.put("dd.civisibility.build.instrumentation.enabled", "false");
      generated.put("traceparent", "private-parent-context");
      generated.put("tracestate", "dd=s:1");
      generated.put("baggage", "a=b");
      generated.put("dd.api-key", "owned-secret-marker");
      @SuppressWarnings("unchecked")
      Map<String, String> marked =
          (Map<String, String>)
              runtime.getMethod("generatedProperties", Map.class).invoke(null, generated);
      assertTrue(marked.values().stream().noneMatch("owned-secret-marker"::equals));
      List<String> stockArguments = new java.util.ArrayList<>();
      marked.forEach((key, value) -> stockArguments.add("-D" + key + "=" + value));
      stockArguments.add("-javaagent:owned.jar");
      stockArguments.add("-Xmx256m");
      @SuppressWarnings("unchecked")
      List<String> arguments =
          (List<String>)
              runtime.getMethod("childArguments", Iterable.class).invoke(null, stockArguments);
      assertEquals(
          stockArguments.subList(stockArguments.size() - 2, stockArguments.size()),
          arguments.subList(0, 2));
      String child = arguments.get(2).substring("-Dtracing.observer.child.v1=".length());
      assertTrue(
          !new String(java.util.Base64.getUrlDecoder().decode(child), "UTF-8")
              .contains("owned-secret-marker"));
      Files.write(file, "dd.service=file-worker\n".getBytes("ISO-8859-1"));
      System.setProperty("tracing.observer.child.v1", child);
      try (SourceLoader worker = loader(true)) {
        worker.loadClass(Environment.class.getName()).getField("values").set(null, environment);
        Class<?> provider = worker.loadClass(OBSERVER + "bootstrap.config.provider.ConfigProvider");
        Object config = provider.getMethod("createDefault").invoke(null);
        assertEquals("private-environment", configString(provider, config, "env"));
        assertEquals("file-worker", configString(provider, config, "service"));
        assertEquals("owned-secret-marker", configString(provider, config, "api-key"));
        assertEquals(
            "false", configString(provider, config, "civisibility.build.instrumentation.enabled"));
        Class<?> workerRuntime =
            worker.loadClass("datadog.trace.observer.bootstrap.ObserverRuntime");
        Map<?, ?> carrier =
            (Map<?, ?>) workerRuntime.getMethod("propagationProperties").invoke(null);
        assertEquals("private-parent-context", carrier.get("traceparent"));
        assertEquals("dd=s:1", carrier.get("tracestate"));
        assertEquals("a=b", carrier.get("baggage"));
      }
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void artifactRetainsCatalogCapabilitiesAndStockGradleArgumentBody() throws Exception {
    try (java.util.jar.JarFile stock =
            new java.util.jar.JarFile(System.getProperty("observer.test.stock"));
        java.util.jar.JarFile observer =
            new java.util.jar.JarFile(System.getProperty("observer.test.artifact"))) {
      java.io.DataInputStream stockIndex =
          new java.io.DataInputStream(
              stock.getInputStream(stock.getJarEntry("inst/instrumenter.index")));
      java.io.DataInputStream observerIndex =
          new java.io.DataInputStream(
              observer.getInputStream(observer.getJarEntry("inst/observer-instrumenter.index")));
      int modules = stockIndex.readInt();
      assertTrue(modules > 6);
      assertEquals(modules, observerIndex.readInt());
      assertEquals(stockIndex.readInt(), observerIndex.readInt());
      for (String[] seam :
          new String[][] {
            {"junit5/JUnitPlatformUtils", "capabilities"},
            {"gradle/CiVisibilityService", "getTracerJvmArgs"},
            {"gradle/CiVisibilityPluginExtension", "applyJacocoSettings"},
            {"gradle/TracerArgumentsProvider", "replaceProjectProperties"}
          }) {
        List<List<Integer>> bodies = new java.util.ArrayList<>();
        for (boolean relocated : new boolean[] {false, true}) {
          java.util.jar.JarFile jar = relocated ? observer : stock;
          String resource =
              "inst/datadog/"
                  + (relocated ? "trace/observer/" : "")
                  + "trace/instrumentation/"
                  + seam[0]
                  + ".classdata";
          ClassNode node = new ClassNode();
          new ClassReader(SourceLoader.read(jar.getInputStream(jar.getJarEntry(resource))))
              .accept(node, 0);
          MethodNode method =
              node.methods.stream().filter(m -> m.name.equals(seam[1])).findFirst().get();
          List<Integer> body = new java.util.ArrayList<>();
          for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode
                && ((MethodInsnNode) instruction).name.equals("generatedProperties"))) {
              body.add(instruction.getOpcode());
            }
          }
          bodies.add(body);
        }
        assertEquals(bodies.get(0), bodies.get(1), seam[0] + "." + seam[1]);
      }
    }
  }

  @Test
  void artifactKeepsExternalLiteralsThatLookLikePackages() throws Exception {
    try (java.util.jar.JarFile observer =
        new java.util.jar.JarFile(System.getProperty("observer.test.artifact"))) {
      for (String[] expected :
          new String[][] {
            {
              "datadog/trace/observer/trace/api/ConfigDefaults.class", "/var/run/datadog/apm.socket"
            },
            {"datadog/trace/observer/trace/api/Config.class", ".inject.datadog.attribute.enabled"},
            {
              "trace/datadog/trace/observer/trace/agent/core/otlp/metrics/OtlpStatsMetricWriter.classdata",
              "datadog.origin"
            },
            {
              "logs-intake/datadog/trace/observer/trace/logging/intake/LogsWriterImpl.classdata",
              "datadog.product:"
            }
          }) {
        java.util.jar.JarEntry entry = observer.getJarEntry(expected[0]);
        assertTrue(entry != null, expected[0]);
        List<Object> constants = new java.util.ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(SourceLoader.read(observer.getInputStream(entry))).accept(node, 0);
        node.fields.forEach(field -> constants.add(field.value));
        for (MethodNode method : node.methods) {
          for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode) {
              constants.add(((org.objectweb.asm.tree.LdcInsnNode) instruction).cst);
            }
          }
        }
        assertTrue(constants.contains(expected[1]), expected[0] + " " + expected[1]);
      }
    }
  }

  @Test
  void configReadsOwnedCredentialFilesWithStockSemantics(@TempDir Path directory) throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    Path key = directory.resolve("owned-dummy.key");
    Files.write(key, "offline-credential-dummy\n".getBytes("UTF-8"));
    try {
      for (String spelling :
          new String[] {
            "dd.api-key-file", "dd.profiling.api-key-file", "dd.profiling.apikey.file"
          }) {
        System.setProperties((Properties) original.clone());
        System.setProperty("tracing.observer.config." + spelling, key.toString());
        try (SourceLoader loader = loader(true)) {
          Class<?> config = loader.loadClass(OBSERVER + "api.Config");
          Object instance = config.getMethod("get").invoke(null);
          assertEquals(
              "offline-credential-dummy", config.getMethod("getApiKey").invoke(instance), spelling);
        }
      }
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void stockAgentArgumentsAndOtelFileInputsRemainPrivate(@TempDir Path directory) throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    Path otel = directory.resolve("owned-otel.properties");
    Files.write(
        otel,
        "otel.service.name=otel-file\notel.exporter.otlp.endpoint=https://example.invalid\n"
            .getBytes("ISO-8859-1"));
    try (SourceLoader loader = loader(true)) {
      System.setProperty("tracing.observer.config.dd.trace.otel.enabled", "true");
      System.setProperty(
          "tracing.observer.config.otel.javaagent.configuration-file", otel.toString());
      System.setProperty("dd.service", "subject");
      Class<?> provider = loader.loadClass(OBSERVER + "bootstrap.config.provider.ConfigProvider");
      Object initial = provider.getMethod("createDefault").invoke(null);
      assertEquals("true", configString(provider, initial, "trace.otel.enabled"));
      assertEquals("otel-file", configString(provider, initial, "service.name"));
      Class<?> injector =
          loader.loadClass(OBSERVER + "bootstrap.config.provider.AgentArgsInjector");
      injector
          .getMethod("injectAgentArgsConfig", String.class)
          .invoke(null, "dd.service=ordinary,dd.site=datad0g.com");
      Object config = provider.getMethod("createDefault").invoke(null);
      assertEquals("ordinary", configString(provider, config, "service"));
      assertEquals("datad0g.com", configString(provider, config, "site"));
      assertEquals("subject", System.getProperty("dd.service"));
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void generatedSettingsUseStockPlaceholderSubstitutionBeforeEncoding() throws Exception {
    String name = OBSERVER + "instrumentation.gradle.TracerArgumentsProvider";
    ClassNode node = new ClassNode();
    try (java.util.jar.JarFile jar =
        new java.util.jar.JarFile(System.getProperty("observer.test.artifact"))) {
      new ClassReader(
              SourceLoader.read(
                  jar.getInputStream(
                      jar.getJarEntry("inst/" + name.replace('.', '/') + ".classdata"))))
          .accept(node, 0);
    }
    // Make only the abstract Gradle service accessor concrete so the real substitution method
    // can be exercised without starting Gradle or either tracer.
    node.access &= ~Opcodes.ACC_ABSTRACT;
    MethodNode service =
        node.methods.stream()
            .filter(m -> m.name.equals("getCiVisibilityService"))
            .findFirst()
            .get();
    service.access &= ~Opcodes.ACC_ABSTRACT;
    service.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
    service.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    class FixtureLoader extends ClassLoader {
      FixtureLoader() {
        super(ObserverConfigSourcesTest.class.getClassLoader());
      }

      Class<?> define() {
        byte[] bytes = writer.toByteArray();
        return defineClass(name, bytes, 0, bytes.length);
      }
    }
    Class<?> provider = new FixtureLoader().define();
    Object instance =
        provider
            .getConstructor(String.class, Map.class)
            .newInstance(":test", singletonMap("fixture", "resolved $ value"));
    Method replace = provider.getDeclaredMethod("replaceProjectProperties", String.class);
    replace.setAccessible(true);
    try (SourceLoader loader = loader(true)) {
      Class<?> runtime = loader.loadClass("datadog.trace.observer.bootstrap.ObserverRuntime");
      @SuppressWarnings("unchecked")
      Map<String, String> marked =
          (Map<String, String>)
              runtime
                  .getMethod("generatedProperties", Map.class)
                  .invoke(null, singletonMap("dd.tags", "tag:${fixture}"));
      List<String> arguments = new java.util.ArrayList<>();
      for (Map.Entry<String, String> entry : marked.entrySet()) {
        arguments.add(
            (String) replace.invoke(instance, "-D" + entry.getKey() + "=" + entry.getValue()));
      }
      arguments.add((String) replace.invoke(instance, "-Dadditional=${fixture}"));
      @SuppressWarnings("unchecked")
      List<String> result =
          (List<String>)
              runtime.getMethod("childArguments", Iterable.class).invoke(null, arguments);
      assertEquals("-Dadditional=resolved $ value", result.get(0));
      String decoded =
          new String(
              java.util.Base64.getUrlDecoder()
                  .decode(result.get(1).substring("-Dtracing.observer.child.v1=".length())),
              "UTF-8");
      assertTrue(decoded.contains("tag:resolved $ value"));
      assertTrue(!decoded.contains("${fixture}"));
    }
    MethodNode asArguments =
        node.methods.stream().filter(m -> m.name.equals("asArguments")).findFirst().get();
    List<String> calls = new java.util.ArrayList<>();
    for (AbstractInsnNode instruction : asArguments.instructions) {
      if (instruction instanceof MethodInsnNode) {
        calls.add(((MethodInsnNode) instruction).name);
      }
    }
    assertEquals("collect", calls.get(calls.size() - 2));
    assertEquals("childArguments", calls.get(calls.size() - 1));
  }

  private static void initializeParent(Class<?> runtime) throws Exception {
    Method encode = runtime.getDeclaredMethod("encode", List.class);
    encode.setAccessible(true);
    String envelope =
        (String) encode.invoke(null, asList(emptyMap(), emptyMap(), emptyMap(), emptyMap()));
    runtime.getMethod("initializePremain", String.class).invoke(null, "v1:" + envelope);
  }

  private static Object configString(Class<?> provider, Object config, String key)
      throws Exception {
    return provider.getMethod("getString", String.class).invoke(config, key);
  }

  private Map<String, Object> evaluate(boolean observer, String factory, Path file)
      throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    try (SourceLoader loader = loader(observer)) {
      String prefix = observer ? "tracing.observer.config." : "";
      System.setProperty(prefix + "dd.service.name", "high-alias");
      System.setProperty(prefix + "dd.observer.probe.integer", "invalid-number");
      System.setProperty(prefix + "dd.observer.probe.boolean", "invalid-boolean");
      System.setProperty(prefix + "dd.tags", "prop:yes,shared:property");
      System.setProperty(prefix + "dd.api-key", "ignored-property-dummy");
      if (file != null) {
        System.setProperty(prefix + "dd.trace.config", file.toString());
      }
      Map<String, String> env = new HashMap<>();
      String ep = observer ? "TRACING_OBSERVER_CONFIG_" : "";
      env.put(ep + "DD_SERVICE", "env-service");
      env.put(ep + "DD_OBSERVER_PROBE_INTEGER", "71");
      env.put(ep + "DD_OBSERVER_PROBE_BOOLEAN", "true");
      env.put(ep + "DD_TAGS", "env:yes,shared:environment");
      env.put(ep + "DD_API_KEY", "env-dummy");
      env.put(ep + "DD_APP_KEY", "app-dummy");
      loader.loadClass(Environment.class.getName()).getField("values").set(null, env);
      String name = observer ? OBSERVER : STOCK;
      Class<?> provider = loader.loadClass(name + "bootstrap.config.provider.ConfigProvider");
      Properties provided = new Properties();
      provided.setProperty("service", "provided");
      Object instance =
          factory.equals("withPropertiesOverride")
              ? provider.getMethod(factory, Properties.class).invoke(null, provided)
              : provider.getMethod(factory).invoke(null);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put(
          "service",
          provider
              .getMethod("getString", String.class, String.class, String[].class)
              .invoke(instance, "service", null, new String[] {"service.name"}));
      result.put(
          "numeric",
          provider
              .getMethod("getInteger", String.class, int.class, String[].class)
              .invoke(instance, "observer.probe.integer", 9, new String[0]));
      result.put(
          "boolean",
          provider
              .getMethod("getBoolean", String.class, boolean.class, String[].class)
              .invoke(instance, "observer.probe.boolean", true, new String[0]));
      result.put(
          "tags",
          provider
              .getMethod("getMergedMap", String.class, String[].class)
              .invoke(instance, "tags", new String[0]));
      Class<?> system =
          loader.loadClass(name + "bootstrap.config.provider.SystemPropertiesConfigSource");
      result.put(
          "key",
          provider
              .getMethod(
                  "getStringExcludingSource",
                  String.class,
                  String.class,
                  Class.class,
                  String[].class)
              .invoke(instance, "api-key", null, system, new String[0]));
      result.put(
          "applicationKey",
          provider
              .getMethod(
                  "getStringExcludingSource",
                  String.class,
                  String.class,
                  Class.class,
                  String[].class)
              .invoke(instance, "application-key", null, system, new String[] {"app-key"}));
      assertEquals("app-dummy", result.get("applicationKey"));
      Field collect = provider.getDeclaredField("collectConfig");
      collect.setAccessible(true);
      result.put("collect", collect.get(instance));
      return result;
    } finally {
      System.setProperties(original);
    }
  }

  private SourceLoader loader(boolean observer) throws Exception {
    Path artifact = Paths.get(System.getProperty("observer.test.artifact"));
    Path stock = Paths.get(System.getProperty("observer.test.stock"));
    return new SourceLoader(observer, observer ? artifact : stock, artifact);
  }

  private static class SourceLoader extends URLClassLoader {
    private final boolean observer;
    private final Path artifact;

    SourceLoader(boolean observer, Path jar, Path artifact) throws Exception {
      super(
          new URL[] {
            jar.toUri().toURL(),
            Environment.class.getProtectionDomain().getCodeSource().getLocation()
          },
          null);
      this.observer = observer;
      this.artifact = artifact;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      String resource = name.replace('.', '/') + ".class";
      try {
        byte[] bytes;
        if (!observer && name.equals(STOCK + "bootstrap.config.provider.StableConfigSource")) {
          // Only the host-file seam is disabled in the stock oracle. All factory/parsing code is
          // stock.
          try (java.util.jar.JarFile jar = new java.util.jar.JarFile(artifact.toFile())) {
            bytes =
                read(
                    jar.getInputStream(
                        jar.getJarEntry(resource.replace("datadog/", "datadog/trace/observer/"))));
          }
          ClassWriter writer = new ClassWriter(0);
          new ClassReader(bytes)
              .accept(
                  new ClassRemapper(
                      writer,
                      new Remapper(Opcodes.ASM9) {
                        @Override
                        public String map(String value) {
                          return value.replace("datadog/trace/observer/", "datadog/");
                        }
                      }),
                  0);
          bytes = writer.toByteArray();
        } else {
          URL url = findResource(resource);
          if (url == null) {
            throw new ClassNotFoundException(name);
          }
          try (InputStream in = url.openStream()) {
            bytes = read(in);
          }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (!name.equals(Environment.class.getName())) {
          for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
              if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals("java/lang/System") && call.name.equals("getenv")) {
                  call.owner = Environment.class.getName().replace('.', '/');
                }
              }
            }
          }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        bytes = writer.toByteArray();
        return defineClass(name, bytes, 0, bytes.length);
      } catch (Exception e) {
        throw new ClassNotFoundException(name, e);
      }
    }

    private static byte[] read(InputStream in) throws Exception {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        bytes.write(buffer, 0, n);
      }
      return bytes.toByteArray();
    }
  }
}
