package datadog.gradle.plugin.observer;

import static java.util.Arrays.asList;
import static java.util.Collections.sort;

import datadog.trace.observer.bootstrap.ObserverBootstrap;
import datadog.trace.observer.bootstrap.ObserverRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Offline, layout-checked namespace isolation of the stock agent. */
public final class ObserverAgentRewriter {
  private static final String RUNTIME = "datadog/trace/observer/bootstrap/ObserverRuntime";
  private static final String PROVIDER = "datadog/trace/bootstrap/config/provider/ConfigProvider";
  private static final String JUNIT = "datadog/trace/instrumentation/junit5/";
  private static final String GRADLE = "datadog/trace/instrumentation/gradle/";
  private static final Set<String> SYSTEM_METHODS =
      new HashSet<>(
          asList("getProperty", "getProperties", "setProperty", "clearProperty", "getenv"));

  /** Host paths, config key fragments and wire names that only look like relocatable packages. */
  private static final Set<String> EXTERNAL_LITERALS =
      new HashSet<>(
          asList(
              "/var/run/datadog/apm.socket",
              "/var/run/datadog/dsd.socket",
              ".inject.datadog.attribute.enabled",
              "datadog.product:",
              "datadog.tracer.stats.collapsed_spans",
              "datadog.jvm.runtime",
              "datadog.operation.name",
              "datadog.span.type",
              "datadog.span.top_level",
              "datadog.is_trace_root",
              "datadog.svc_src",
              "datadog.origin",
              "datadog.peer_tags",
              "datadog.sdk.semantics",
              "datadog.runtime_id",
              "datadog.process_tags"));

  private static final String DOGSTATSD_METRICS = "datadog.dogstatsd.client.";

  private final Set<String> patches = new HashSet<>();
  private final Set<String> configAliases = new TreeSet<>();
  private final Set<String> sensitiveConfig = new TreeSet<>();

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected stock-agent.jar observer-agent.jar");
    }
    new ObserverAgentRewriter().rewrite(new File(args[0]), new File(args[1]));
  }

  public void rewrite(File input, File output) throws Exception {
    if (input.getCanonicalFile().equals(output.getCanonicalFile())) {
      throw new IllegalArgumentException("Never overwrite the stock agent");
    }
    patches.clear();
    configAliases.clear();
    sensitiveConfig.clear();
    Map<String, byte[]> entries = new LinkedHashMap<>();
    Manifest manifest;
    try (JarFile jar = new JarFile(input)) {
      manifest = new Manifest(jar.getManifest());
      Attributes attributes = manifest.getMainAttributes();
      require(
          "datadog.trace.bootstrap.AgentPreCheck".equals(attributes.getValue("Premain-Class")),
          "Expected a stock agent manifest");
      for (String key : asList("Premain-Class", "Agent-Class", "Main-Class")) {
        String value = attributes.getValue(key);
        if (value != null) {
          attributes.putValue(key, relocate(value));
        }
      }
      attributes.putValue("Tracing-The-Tracer-Observer", "2");
      attributes.putValue("Premain-Class", ObserverBootstrap.class.getName());
      attributes.remove(new Attributes.Name("Agent-Class"));
      for (String required :
          asList(
              "datadog/trace/bootstrap/AgentBootstrap.class",
              "datadog/trace/bootstrap/AgentPreCheck.class",
              PROVIDER + ".class",
              "datadog/trace/bootstrap/config/provider/PropertiesConfigSource.class",
              "dd-java-agent.index",
              "inst/instrumenter.index",
              "inst/known-types.index")) {
        require(jar.getJarEntry(required) != null, "Missing expected stock entry: " + required);
      }
      Enumeration<JarEntry> source = jar.entries();
      while (source.hasMoreElements()) {
        JarEntry entry = source.nextElement();
        String name = entry.getName();
        // Rebuild known types from the relocated full instrumenter index at startup.
        if (entry.isDirectory()
            || name.equals("META-INF/MANIFEST.MF")
            || name.equals("dd-java-agent.index")
            || name.equals("inst/known-types.index")) {
          continue;
        }
        byte[] bytes;
        try (InputStream stream = jar.getInputStream(entry)) {
          bytes = readAll(stream);
        }
        if (name.endsWith(".class") || name.endsWith(".classdata")) {
          bytes = rewriteClass(bytes);
        } else if (name.equals("inst/instrumenter.index")) {
          bytes = rewriteInstrumenterIndex(bytes);
          patches.add("instrumenter.index");
        } else if (name.contains("META-INF/services/")) {
          bytes =
              relocate(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        }
        String targetName = relocate(name);
        if (name.equals("simplelogger.properties") || name.endsWith("/simplelogger.properties")) {
          targetName =
              targetName.substring(0, targetName.length() - "simplelogger.properties".length())
                  + "observer-simplelogger.properties";
        }
        require(entries.put(targetName, bytes) == null, "Duplicate relocated entry: " + name);
      }
    }
    Set<String> expected =
        new HashSet<>(
            asList(
                "instrumenter.index",
                "createDefault",
                "withoutCollector",
                "withPropertiesOverride",
                "stable-source",
                "child-arguments",
                "generated-properties",
                "private-carrier",
                "numeric-ipc-host",
                "generated-file-logger",
                "private-logger-resource",
                "logger-resource-keys",
                "JUnit5Instrumentation$JUnit5Advice",
                "JUnit5SpockInstrumentation$SpockAdvice"));
    require(
        patches.equals(expected),
        "Input agent layout changed: expected patches " + expected + ", got " + patches);
    try (InputStream stream = ObserverRuntime.class.getResourceAsStream("ObserverRuntime.class")) {
      entries.put(RUNTIME + ".class", readAll(stream));
    }
    try (InputStream stream =
        ObserverBootstrap.class.getResourceAsStream("ObserverBootstrap.class")) {
      entries.put("datadog/trace/observer/bootstrap/ObserverBootstrap.class", readAll(stream));
    }
    require(!configAliases.isEmpty(), "Missing generated configuration metadata");
    entries.put(
        "observer-config-aliases.txt",
        String.join("\n", configAliases).getBytes(StandardCharsets.UTF_8));
    require(!sensitiveConfig.isEmpty(), "Missing sensitive configuration metadata");
    entries.put(
        "observer-sensitive-config.txt",
        String.join("\n", sensitiveConfig).getBytes(StandardCharsets.UTF_8));
    entries.put("dd-observer-agent.index", buildJarIndex(input, entries));
    Path target = output.getAbsoluteFile().toPath();
    Files.createDirectories(target.getParent());
    // A JVM may still be running from the previous jar, so never truncate it in place.
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temporary), manifest)) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
          JarEntry jarEntry = new JarEntry(entry.getKey());
          jarEntry.setTime(0);
          jar.putNextEntry(jarEntry);
          jar.write(entry.getValue());
          jar.closeEntry();
        }
      }
      Files.move(
          temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static String relocate(String value) {
    if (EXTERNAL_LITERALS.contains(value) || value.startsWith(DOGSTATSD_METRICS)) {
      return value;
    }
    return value
        .replace("com.datadog.", "__OBSERVER_COM_DOT__")
        .replace("com/datadog/", "__OBSERVER_COM_SLASH__")
        .replace("datadog.", "datadog.trace.observer.")
        .replace("datadog/", "datadog/trace/observer/")
        .replace("__OBSERVER_COM_DOT__", "datadog.trace.observer.com.datadog.")
        .replace("__OBSERVER_COM_SLASH__", "datadog/trace/observer/com/datadog/")
        .replace("net.bytebuddy.", "datadog.trace.observer.net.bytebuddy.")
        .replace("net/bytebuddy/", "datadog/trace/observer/net/bytebuddy/")
        .replace("dd-java-agent.index", "dd-observer-agent.index")
        .replace("dd-java-agent.version", "dd-observer-agent.version")
        .replace("instrumenter.index", "observer-instrumenter.index")
        .replace("known-types.index", "observer-known-types.index")
        .replace("__datadogContext$", "__datadogObserverContext$");
  }

  byte[] rewriteClass(byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    if (node.name.equals(PROVIDER)) {
      require(
          node.methods.stream()
              .anyMatch(
                  method ->
                      method.name.equals("<init>")
                          && method.desc.equals("([L" + PROVIDER + "$Source;)V")),
          "ConfigProvider constructor changed");
    }
    for (MethodNode method : node.methods) {
      patchGradleBoundary(node, method);
      if (node.name.equals("datadog/trace/logging/simplelogger/SLCompatSettings")
          && method.name.equals("loadProperties")) {
        require(
            method.desc.equals("(Ljava/lang/String;)Ljava/util/Properties;"),
            "Logger resource parser changed");
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
          if (instruction.getOpcode() == Opcodes.ARETURN) {
            method.instructions.insertBefore(
                instruction,
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    RUNTIME,
                    "loggerProperties",
                    "(Ljava/util/Properties;)Ljava/util/Properties;",
                    false));
          }
        }
        patches.add("logger-resource-keys");
      }
      if (node.name.equals("datadog/trace/bootstrap/config/provider/StableConfigSource")
          && method.name.equals("<init>")) {
        require(
            method.desc.equals("(Ljava/lang/String;Ldatadog/trace/api/ConfigOrigin;)V"),
            "Stable source changed");
        clearBody(method);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(
            new MethodInsnNode(
                Opcodes.INVOKESPECIAL, PROVIDER + "$Source", "<init>", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(
            new FieldInsnNode(
                Opcodes.PUTFIELD, node.name, "fileOrigin", "Ldatadog/trace/api/ConfigOrigin;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(
            new FieldInsnNode(
                Opcodes.GETSTATIC,
                node.name + "$StableConfig",
                "EMPTY",
                "L" + node.name + "$StableConfig;"));
        code.add(
            new FieldInsnNode(
                Opcodes.PUTFIELD, node.name, "config", "L" + node.name + "$StableConfig;"));
        code.add(new InsnNode(Opcodes.RETURN));
        patches.add("stable-source");
      }
      if (node.name.equals(PROVIDER)
          && asList("createDefault", "withoutCollector", "withPropertiesOverride")
              .contains(method.name)) {
        String expected =
            method.name.equals("withPropertiesOverride")
                ? "(Ljava/util/Properties;)L" + PROVIDER + ";"
                : "()L" + PROVIDER + ";";
        require(method.desc.equals(expected), "ConfigProvider signature changed: " + method.name);
        // Preserve the complete stock source chain, factory flags and caller overrides.
        patches.add(method.name);
      }
      if ((node.name.equals(JUNIT + "JUnit5Instrumentation$JUnit5Advice")
              || node.name.equals(JUNIT + "JUnit5SpockInstrumentation$SpockAdvice"))
          && method.name.equals("addTracingListener")) {
        require(
            method.desc.equals(
                "(Lorg/junit/platform/engine/TestEngine;Lorg/junit/platform/engine/ExecutionRequest;)V"),
            "Advice signature changed");
        InsnList guard = new InsnList();
        LabelNode accepted = new LabelNode();
        // Already relocated: the remapper below deliberately leaves this bridge name unchanged.
        guard.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "isOuterGradleExecution", "()Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, accepted));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(accepted);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(guard);
        patches.add(node.name.substring(JUNIT.length()));
      }
      for (AbstractInsnNode instruction : method.instructions) {
        if (node.name.endsWith("/GeneratedSupportedConfigurations")
            && method.name.startsWith("initSensitiveKeys")
            && instruction instanceof LdcInsnNode
            && ((LdcInsnNode) instruction).cst instanceof String) {
          sensitiveConfig.add((String) ((LdcInsnNode) instruction).cst);
        }

        if (node.name.endsWith("/GeneratedSupportedConfigurations")
            && (method.name.startsWith("initAliasMapping")
                || method.name.startsWith("initDeprecated"))
            && instruction instanceof LdcInsnNode) {
          Object value = ((LdcInsnNode) instruction).cst;
          if (value instanceof String && !((String) value).isEmpty()) {
            configAliases.add((String) value);
          }
        }
        if (instruction instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) instruction;
          if ((call.owner.equals("java/lang/System") && SYSTEM_METHODS.contains(call.name))
              || (call.owner.equals("java/lang/Boolean") && call.name.equals("getBoolean"))) {
            call.owner = RUNTIME;
          }
        }
      }
    }
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(
        new ClassRemapper(
            writer,
            new Remapper(Opcodes.ASM9) {
              @Override
              public String map(String name) {
                return name.equals(RUNTIME) ? name : relocate(name);
              }

              @Override
              public String mapFieldName(String owner, String name, String descriptor) {
                return name.replace("__datadogContext$", "__datadogObserverContext$");
              }

              @Override
              public String mapMethodName(String owner, String name, String descriptor) {
                return name.replace("__datadogContext$", "__datadogObserverContext$");
              }

              @Override
              public String mapInvokeDynamicMethodName(
                  String name, String descriptor, Handle bootstrapMethod, Object... arguments) {
                return super.mapInvokeDynamicMethodName(
                        name, descriptor, bootstrapMethod, arguments)
                    .replace("__datadogContext$", "__datadogObserverContext$");
              }

              @Override
              public Object mapValue(Object value) {
                if (value instanceof String) {
                  String text = (String) value;
                  if (node.name.startsWith("datadog/trace/logging/simplelogger/SLCompatSettings")
                      && text.equals("simplelogger.properties")) {
                    patches.add("private-logger-resource");
                    return "observer-simplelogger.properties";
                  }
                  if (node.name.startsWith(GRADLE)) {
                    if (text.equals("ciVisibilityService")) {
                      return "observerCiVisibilityService";
                    }
                    if (text.equals("dd-ci-visibility")) {
                      return "observer-ci-visibility";
                    }
                    if (text.equals("moduleLayout")) {
                      return "observerModuleLayout";
                    }
                  }
                  return relocate(text);
                }
                if (value instanceof Handle) {
                  Handle handle = (Handle) value;
                  if ((handle.getOwner().equals("java/lang/System")
                          && SYSTEM_METHODS.contains(handle.getName()))
                      || (handle.getOwner().equals("java/lang/Boolean")
                          && handle.getName().equals("getBoolean"))) {
                    return new Handle(
                        handle.getTag(), RUNTIME, handle.getName(), handle.getDesc(), false);
                  }
                }
                return super.mapValue(value);
              }
            }));
    return writer.toByteArray();
  }

  private static void clearBody(MethodNode method) {
    method.instructions.clear();
    method.tryCatchBlocks.clear();
    if (method.localVariables != null) {
      method.localVariables.clear();
    }
  }

  private void patchGradleBoundary(ClassNode node, MethodNode method) {
    if (node.name.equals(GRADLE + "GradleDaemonLoggingInstrumentation$ReinitialiseLogging")
        && method.name.equals("reinitialiseTracerLogging")) {
      require(
          method.desc.equals("()V") && (method.access & Opcodes.ACC_STATIC) != 0,
          "Gradle logging reset seam changed");
      InsnList guard = new InsnList();
      LabelNode reset = new LabelNode();
      guard.add(
          new MethodInsnNode(
              Opcodes.INVOKESTATIC, RUNTIME, "usingGeneratedFileLogger", "()Z", false));
      guard.add(new JumpInsnNode(Opcodes.IFEQ, reset));
      guard.add(new InsnNode(Opcodes.RETURN));
      guard.add(reset);
      guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
      method.instructions.insert(guard);
      patches.add("generated-file-logger");
    }
    if (node.name.equals(GRADLE + "CiVisibilityService")
        && method.name.equals("getTracerJvmArgs")) {
      require(
          method.desc.equals("(Ljava/lang/String;)Ljava/util/Collection;"),
          "Gradle worker transport changed");
      for (AbstractInsnNode instruction : method.instructions.toArray()) {
        if (instruction instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) instruction;
          if (call.owner.equals("datadog/trace/api/civisibility/domain/BuildModuleSettings")
              && call.name.equals("getSystemProperties")) {
            method.instructions.insert(
                call,
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    RUNTIME,
                    "generatedProperties",
                    "(Ljava/util/Map;)Ljava/util/Map;",
                    false));
            patches.add("generated-properties");
          }
        }
      }
    }
    if (node.name.equals(GRADLE + "TracerArgumentsProvider") && method.name.equals("asArguments")) {
      require(method.desc.equals("()Ljava/lang/Iterable;"), "Gradle argument provider changed");
      for (AbstractInsnNode instruction : method.instructions.toArray()) {
        if (instruction.getOpcode() == Opcodes.ARETURN) {
          method.instructions.insertBefore(
              instruction,
              new MethodInsnNode(
                  Opcodes.INVOKESTATIC,
                  RUNTIME,
                  "childArguments",
                  "(Ljava/lang/Iterable;)Ljava/lang/Iterable;",
                  false));
          patches.add("child-arguments");
        }
      }
    }
    if (node.name.equals("datadog/trace/civisibility/domain/buildsystem/BuildSystemModuleImpl")
        && method.name.equals("getPropertiesPropagatedToChildProcess")) {
      for (AbstractInsnNode instruction : method.instructions.toArray()) {
        if (instruction instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) instruction;
          if (call.owner.equals("java/net/InetSocketAddress") && call.name.equals("getHostName")) {
            call.name = "getAddress";
            call.desc = "()Ljava/net/InetAddress;";
            method.instructions.insert(
                call,
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/net/InetAddress",
                    "getHostAddress",
                    "()Ljava/lang/String;",
                    false));
            patches.add("numeric-ipc-host");
          }
        }
      }
    }
    if (node.name.equals("datadog/trace/civisibility/ProcessHierarchy")
        && method.name.equals("<init>")) {
      for (AbstractInsnNode instruction : method.instructions.toArray()) {
        if (instruction instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) instruction;
          if (call.owner.equals("datadog/environment/SystemProperties")
              && call.name.equals("asStringMap")) {
            require(call.desc.equals("()Ljava/util/Map;"), "Process carrier changed");
            call.owner = RUNTIME;
            call.name = "propagationProperties";
            patches.add("private-carrier");
          }
        }
      }
    }
  }

  /** Parse the current packed-name format, including member-level target-system overrides. */
  static byte[] rewriteInstrumenterIndex(byte[] bytes) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    int modules = in.readInt();
    int transformations = in.readInt();
    require(
        modules > 0 && modules < 10000 && transformations > 0, "Invalid instrumenter index header");
    require(in.readInt() == in.available(), "Invalid packed-name length");
    ByteArrayOutputStream packed = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(packed);
    Set<String> found = new HashSet<>();
    int actualTransformations = 0;
    for (int i = 0; i < modules; i++) {
      String module = readName(in);
      ByteArrayOutputStream record = new ByteArrayOutputStream();
      DataOutputStream target = new DataOutputStream(record);
      writeName(target, relocate(module));
      target.writeShort(in.readUnsignedShort());
      int flags = in.readUnsignedByte();
      require((flags & ~3) == 0, "Unknown module flags");
      target.writeByte(flags);
      int members = in.readUnsignedByte();
      target.writeByte(members);
      for (int member = 0; member < (members == 255 ? 1 : members); member++) {
        if (members != 255) {
          writeName(target, readName(in));
        }
        if ((flags & 1) != 0) {
          int overrides = in.readUnsignedByte();
          target.writeByte(overrides);
          for (int o = 0; o < overrides; o++) {
            writeName(target, readName(in));
            target.writeShort(in.readUnsignedShort());
          }
        }
      }
      require(found.add(module), "Duplicate instrumenter module");
      out.write(record.toByteArray());
      actualTransformations += members == 255 ? 1 : members;
    }
    require(
        in.available() == 0 && actualTransformations == transformations,
        "Instrumenter index layout changed");
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream header = new DataOutputStream(result);
    header.writeInt(found.size());
    header.writeInt(transformations);
    header.writeInt(packed.size());
    packed.writeTo(header);
    return result.toByteArray();
  }

  private static String readName(DataInputStream in) throws IOException {
    byte[] bytes = new byte[in.readUnsignedByte()];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  private static void writeName(DataOutputStream out, String name) throws IOException {
    require(name.length() < 256, "Indexed name exceeds one-byte length");
    out.writeByte(name.length());
    out.writeBytes(name);
  }

  /** Rebuild the jar-local trie from final entries, never byte-patch a serialized trie. */
  private static byte[] buildJarIndex(File original, Map<String, byte[]> entries) throws Exception {
    try (URLClassLoader loader = new URLClassLoader(new URL[] {original.toURI().toURL()}, null)) {
      Class<?> builderClass = loader.loadClass("datadog.instrument.utils.ClassNameTrie$Builder");
      Object builder = builderClass.getConstructor().newInstance();
      List<String> prefixes = new ArrayList<>();
      List<String> names = new ArrayList<>(entries.keySet());
      Map<String, Integer> classKeys = new LinkedHashMap<>();
      sort(names);
      for (String name : names) {
        int prefixId = 0;
        String key = name;
        // Stock root entries are bootstrap classes/resources; classdata belongs to a feature root.
        if (!name.startsWith("datadog/") && !name.startsWith("META-INF/") && name.contains("/")) {
          String prefix = name.substring(0, name.indexOf('/') + 1);
          if (!prefixes.contains(prefix)) {
            prefixes.add(prefix);
          }
          prefixId = prefixes.indexOf(prefix) + 1;
          key = name.substring(prefix.length());
        }
        if (key.endsWith(".classdata")) {
          key = key.substring(0, key.length() - ".classdata".length());
        } else if (key.endsWith(".class")) {
          key = key.substring(0, key.length() - ".class".length());
        }
        if (name.endsWith(".class") || name.endsWith(".classdata")) {
          require(
              key.equals(new ClassReader(entries.get(name)).getClassName()),
              "Entry/class identity mismatch: " + name);
          require(
              classKeys.put(key.replace('/', '.'), prefixId) == null,
              "Duplicate class across feature roots: " + name);
        }
        builderClass
            .getMethod("put", String.class, int.class)
            .invoke(builder, key.replace('/', '.'), prefixId);
        // Resource lookup also needs the class suffix.
        String resourceKey =
            (prefixId == 0 ? name : name.substring(prefixes.get(prefixId - 1).length()))
                .replace(".classdata", ".class");
        builderClass
            .getMethod("put", String.class, int.class)
            .invoke(builder, resourceKey.replace('/', '.'), prefixId);
      }
      for (Map.Entry<String, Integer> entry : classKeys.entrySet()) {
        require(
            entry
                .getValue()
                .equals(
                    builderClass.getMethod("apply", String.class).invoke(builder, entry.getKey())),
            "Class index does not resolve " + entry.getKey());
        require(
            entry
                .getValue()
                .equals(
                    builderClass
                        .getMethod("apply", String.class)
                        .invoke(builder, entry.getKey() + ".class")),
            "Resource index does not resolve " + entry.getKey());
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(prefixes.size());
      for (String prefix : prefixes) {
        out.writeUTF(prefix);
      }
      builderClass.getMethod("writeTo", DataOutput.class).invoke(builder, out);
      return bytes.toByteArray();
    }
  }

  private static byte[] readAll(InputStream stream) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = stream.read(buffer)) != -1) {
      bytes.write(buffer, 0, read);
    }
    return bytes.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
