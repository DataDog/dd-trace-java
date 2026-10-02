package datadog.trace.observer.rewriter;

import static java.util.Arrays.asList;

import datadog.trace.observer.bootstrap.ObserverBootstrap;
import datadog.trace.observer.bootstrap.ObserverRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Offline, layout-checked namespace isolation of the stock agent. */
public final class ObserverAgentRewriter {
  private static final String RUNTIME = "datadog/trace/observer/bootstrap/ObserverRuntime";
  private static final String PROVIDER = "datadog/trace/bootstrap/config/provider/ConfigProvider";
  private static final String STABLE_SOURCE =
      "datadog/trace/bootstrap/config/provider/StableConfigSource";
  private static final String LOGGER_SETTINGS =
      "datadog/trace/logging/simplelogger/SLCompatSettings";
  private static final String JUNIT = "datadog/trace/instrumentation/junit5/";
  private static final String GRADLE = "datadog/trace/instrumentation/gradle/";
  private static final String ADD_TRACING_LISTENER =
      "(Lorg/junit/platform/engine/TestEngine;Lorg/junit/platform/engine/ExecutionRequest;)V";
  private static final String STOCK_INDEX = "dd-java-agent.index";

  /** The javac plugin writes these annotation types into user classes, so both tracers share it. */
  private static final String SHARED_ROOT = "datadog/compiler";

  private static final List<String> REQUIRED_ENTRIES =
      asList(
          "datadog/trace/bootstrap/AgentBootstrap.class",
          "datadog/trace/bootstrap/AgentPreCheck.class",
          PROVIDER + ".class",
          "datadog/trace/bootstrap/config/provider/PropertiesConfigSource.class",
          STOCK_INDEX,
          "inst/instrumenter.index",
          "inst/known-types.index");

  private static final Set<String> SYSTEM_METHODS =
      new HashSet<>(
          asList("getProperty", "getProperties", "setProperty", "clearProperty", "getenv"));

  /** Changes one stock method and returns how many places it changed. */
  private interface Patch {
    int apply(ClassNode owner, MethodNode method);
  }

  /** Only checks that the stock method still exists with this signature. */
  private static final Patch VALIDATE = (owner, method) -> 1;

  /** A stock method the observer depends on, and how many times its patch must apply. */
  private static final class Seam {
    final String owner;
    final String key;
    final int expected;
    final Patch patch;

    Seam(String owner, String name, String descriptor, int expected, Patch patch) {
      this.owner = owner;
      this.key = owner + "." + name + descriptor;
      this.expected = expected;
      this.patch = patch;
    }
  }

  /** A string constant renamed in every class under {@code ownerPrefix}. */
  private static final class Rename {
    final String ownerPrefix;
    final String from;
    final String to;
    final int expected;

    Rename(String ownerPrefix, String from, String to, int expected) {
      this.ownerPrefix = ownerPrefix;
      this.from = from;
      this.to = to;
      this.expected = expected;
    }
  }

  private static final List<Seam> SEAMS =
      asList(
          new Seam(PROVIDER, "<init>", "([L" + PROVIDER + "$Source;)V", 1, VALIDATE),
          new Seam(PROVIDER, "createDefault", "()L" + PROVIDER + ";", 1, VALIDATE),
          new Seam(PROVIDER, "withoutCollector", "()L" + PROVIDER + ";", 1, VALIDATE),
          new Seam(
              PROVIDER,
              "withPropertiesOverride",
              "(Ljava/util/Properties;)L" + PROVIDER + ";",
              1,
              VALIDATE),
          new Seam(
              STABLE_SOURCE,
              "<init>",
              "(Ljava/lang/String;Ldatadog/trace/api/ConfigOrigin;)V",
              1,
              ObserverAgentRewriter::observerStableConfig),
          new Seam(
              LOGGER_SETTINGS,
              "loadProperties",
              "(Ljava/lang/String;)Ljava/util/Properties;",
              2,
              beforeReturns("loggerProperties", "(Ljava/util/Properties;)Ljava/util/Properties;")),
          new Seam(
              JUNIT + "JUnit5Instrumentation$JUnit5Advice",
              "addTracingListener",
              ADD_TRACING_LISTENER,
              1,
              returnUnless("isOuterGradleExecution", true)),
          new Seam(
              JUNIT + "JUnit5SpockInstrumentation$SpockAdvice",
              "addTracingListener",
              ADD_TRACING_LISTENER,
              1,
              returnUnless("isOuterGradleExecution", true)),
          new Seam(
              GRADLE + "GradleDaemonLoggingInstrumentation$ReinitialiseLogging",
              "reinitialiseTracerLogging",
              "()V",
              1,
              returnUnless("usingGeneratedFileLogger", false)),
          new Seam(
              GRADLE + "CiVisibilityService",
              "getTracerJvmArgs",
              "(Ljava/lang/String;)Ljava/util/Collection;",
              1,
              afterCalls(
                  "datadog/trace/api/civisibility/domain/BuildModuleSettings",
                  "getSystemProperties",
                  "generatedProperties",
                  "(Ljava/util/Map;)Ljava/util/Map;")),
          new Seam(
              GRADLE + "TracerArgumentsProvider",
              "asArguments",
              "()Ljava/lang/Iterable;",
              1,
              beforeReturns("childArguments", "(Ljava/lang/Iterable;)Ljava/lang/Iterable;")),
          new Seam(
              "datadog/trace/civisibility/domain/buildsystem/BuildSystemModuleImpl",
              "getPropertiesPropagatedToChildProcess",
              "(Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/util/Collection;"
                  + "Ldatadog/trace/api/civisibility/domain/JavaAgent;Ljava/net/InetSocketAddress;"
                  + "Ldatadog/trace/civisibility/config/ExecutionSettings;"
                  + "Ldatadog/trace/api/civisibility/domain/BuildSessionSettings;)Ljava/util/Map;",
              1,
              ObserverAgentRewriter::numericHost),
          new Seam(
              "datadog/trace/civisibility/ProcessHierarchy",
              "<init>",
              "()V",
              1,
              redirectCall(
                  "datadog/environment/SystemProperties",
                  "asStringMap",
                  "()Ljava/util/Map;",
                  "propagationProperties")));

  private static final List<Rename> RENAMES =
      asList(
          new Rename(GRADLE, "ciVisibilityService", "observerCiVisibilityService", 1),
          new Rename(GRADLE, "dd-ci-visibility", "observer-ci-visibility", 2),
          new Rename(GRADLE, "moduleLayout", "observerModuleLayout", 3),
          new Rename(
              LOGGER_SETTINGS, "simplelogger.properties", "observer-simplelogger.properties", 3));

  private final Set<String> roots;
  private final Map<String, Integer> seamCounts = new HashMap<>();
  private final Map<Rename, Integer> renameCounts = new HashMap<>();
  private final Set<String> configAliases = new TreeSet<>();
  private final Set<String> sensitiveConfig = new TreeSet<>();

  ObserverAgentRewriter(Set<String> roots) {
    this.roots = roots;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected stock-agent.jar observer-agent.jar");
    }
    rewrite(new File(args[0]), new File(args[1]));
  }

  public static void rewrite(File input, File output) throws Exception {
    if (input.getCanonicalFile().equals(output.getCanonicalFile())) {
      throw new IllegalArgumentException("Never overwrite the stock agent");
    }
    try (JarFile jar = new JarFile(input)) {
      for (String required : REQUIRED_ENTRIES) {
        require(jar.getJarEntry(required) != null, "Missing expected stock entry: " + required);
      }
      require(
          "datadog.trace.bootstrap.AgentPreCheck"
              .equals(jar.getManifest().getMainAttributes().getValue("Premain-Class")),
          "Expected a stock agent manifest");
      new ObserverAgentRewriter(roots(jar)).rewrite(input, jar, output);
    }
  }

  /** Package roots that exist in the stock jar; only references to these are relocated. */
  static Set<String> roots(JarFile jar) {
    Set<String> roots = new TreeSet<>();
    Enumeration<JarEntry> entries = jar.entries();
    while (entries.hasMoreElements()) {
      List<String> parts = segments(entries.nextElement().getName());
      // Only directories count: the segment after the root must not be the file name.
      for (int i = 0; i + 2 < parts.size(); i++) {
        if (parts.get(i).equals("net") && parts.get(i + 1).equals("bytebuddy")) {
          roots.add("net/bytebuddy");
        }
        if (parts.get(i).equals("datadog")) {
          String parent = i > 0 ? parts.get(i - 1) : "";
          String root = (parent.equals("com") ? "com/datadog/" : "datadog/") + parts.get(i + 1);
          if (!root.equals(SHARED_ROOT)) {
            roots.add(root);
          }
        }
      }
    }
    return roots;
  }

  private static List<String> segments(String name) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', start)) {
      parts.add(name.substring(start, slash));
      start = slash + 1;
    }
    parts.add(name.substring(start));
    return parts;
  }

  private void rewrite(File input, JarFile jar, File output) throws Exception {
    Manifest manifest = new Manifest(jar.getManifest());
    Attributes attributes = manifest.getMainAttributes();
    for (String key : asList("Premain-Class", "Agent-Class", "Main-Class")) {
      String value = attributes.getValue(key);
      if (value != null) {
        attributes.putValue(key, relocate(value));
      }
    }
    attributes.putValue(ObserverRuntime.MANIFEST_ATTRIBUTE, ObserverRuntime.MANIFEST_VERSION);
    attributes.putValue("Premain-Class", ObserverBootstrap.class.getName());
    attributes.remove(new Attributes.Name("Agent-Class"));

    Map<String, byte[]> entries = new LinkedHashMap<>();
    Enumeration<JarEntry> source = jar.entries();
    while (source.hasMoreElements()) {
      JarEntry entry = source.nextElement();
      String name = entry.getName();
      // Known types are rebuilt from the relocated instrumenter index at startup.
      if (entry.isDirectory()
          || name.equals("META-INF/MANIFEST.MF")
          || name.equals(STOCK_INDEX)
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
    checkLayout();

    List<Class<?>> runtimeClasses =
        new ArrayList<>(asList(ObserverRuntime.class, ObserverBootstrap.class));
    runtimeClasses.addAll(asList(ObserverRuntime.class.getDeclaredClasses()));
    for (Class<?> type : runtimeClasses) {
      String resource = type.getName().replace('.', '/') + ".class";
      try (InputStream stream = type.getClassLoader().getResourceAsStream(resource)) {
        entries.put(resource, readAll(stream));
      }
    }
    entries.put(ObserverRuntime.ROOTS_RESOURCE, lines(roots));
    require(!configAliases.isEmpty(), "Missing generated configuration metadata");
    entries.put(ObserverRuntime.ALIASES_RESOURCE, lines(configAliases));
    require(!sensitiveConfig.isEmpty(), "Missing sensitive configuration metadata");
    entries.put(ObserverRuntime.SENSITIVE_RESOURCE, lines(sensitiveConfig));
    List<String> featureRoots = featureRoots(jar);
    byte[] index = buildJarIndex(input, entries, featureRoots);
    entries.put(relocate(STOCK_INDEX), index);
    verifyJarIndex(input, index, entries, featureRoots);
    write(output, manifest, entries);
  }

  /** Every seam and rename must apply exactly as often as it did on the reviewed stock layout. */
  private void checkLayout() {
    Map<String, String> mismatches = new TreeMap<>();
    for (Seam seam : SEAMS) {
      int actual = seamCounts.getOrDefault(seam.key, 0);
      if (actual != seam.expected) {
        mismatches.put(seam.key, "expected " + seam.expected + ", got " + actual);
      }
    }
    for (Rename rename : RENAMES) {
      int actual = renameCounts.getOrDefault(rename, 0);
      if (actual != rename.expected) {
        mismatches.put(
            rename.ownerPrefix + " \"" + rename.from + "\"",
            "expected " + rename.expected + ", got " + actual);
      }
    }
    require(mismatches.isEmpty(), "Input agent layout changed: " + mismatches);
  }

  String relocate(String value) {
    return ObserverRuntime.relocate(value, roots)
        .replace("dd-java-agent.index", "dd-observer-agent.index")
        .replace("dd-java-agent.version", "dd-observer-agent.version")
        .replace("instrumenter.index", "observer-instrumenter.index")
        .replace("known-types.index", "observer-known-types.index")
        .replace("__datadogContext$", "__datadogObserverContext$");
  }

  byte[] rewriteClass(byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    Map<String, Seam> seams = new HashMap<>();
    for (Seam seam : SEAMS) {
      if (seam.owner.equals(node.name)) {
        seams.put(seam.key, seam);
      }
    }
    boolean generatedConfigurations = node.name.endsWith("/GeneratedSupportedConfigurations");
    for (MethodNode method : node.methods) {
      Seam seam = seams.get(node.name + "." + method.name + method.desc);
      if (seam != null) {
        seamCounts.merge(seam.key, seam.patch.apply(node, method), Integer::sum);
      }
      for (AbstractInsnNode instruction : method.instructions) {
        if (generatedConfigurations
            && instruction instanceof LdcInsnNode
            && ((LdcInsnNode) instruction).cst instanceof String) {
          String value = (String) ((LdcInsnNode) instruction).cst;
          if (method.name.startsWith("initSensitiveKeys")) {
            sensitiveConfig.add(value);
          } else if (!value.isEmpty()
              && (method.name.startsWith("initAliasMapping")
                  || method.name.startsWith("initDeprecated"))) {
            configAliases.add(value);
          }
        }
        if (instruction instanceof MethodInsnNode
            && redirectsToRuntime((MethodInsnNode) instruction)) {
          ((MethodInsnNode) instruction).owner = RUNTIME;
        }
      }
    }
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(new ClassRemapper(writer, new ObserverRemapper(node.name)));
    return writer.toByteArray();
  }

  private static boolean redirectsToRuntime(MethodInsnNode call) {
    return (call.owner.equals("java/lang/System") && SYSTEM_METHODS.contains(call.name))
        || (call.owner.equals("java/lang/Boolean") && call.name.equals("getBoolean"));
  }

  private final class ObserverRemapper extends Remapper {
    private final String owner;

    ObserverRemapper(String owner) {
      super(Opcodes.ASM9);
      this.owner = owner;
    }

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
      return super.mapInvokeDynamicMethodName(name, descriptor, bootstrapMethod, arguments)
          .replace("__datadogContext$", "__datadogObserverContext$");
    }

    @Override
    public Object mapValue(Object value) {
      if (value instanceof String) {
        String text = (String) value;
        for (Rename rename : RENAMES) {
          if (owner.startsWith(rename.ownerPrefix) && text.equals(rename.from)) {
            renameCounts.merge(rename, 1, Integer::sum);
            return rename.to;
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
          return new Handle(handle.getTag(), RUNTIME, handle.getName(), handle.getDesc(), false);
        }
      }
      return super.mapValue(value);
    }
  }

  /** Stable config never opens host files; the local source carries the observer's defaults. */
  private static int observerStableConfig(ClassNode node, MethodNode method) {
    method.instructions.clear();
    method.tryCatchBlocks.clear();
    if (method.localVariables != null) {
      method.localVariables.clear();
    }
    String config = node.name + "$StableConfig";
    InsnList code = method.instructions;
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(
        new MethodInsnNode(Opcodes.INVOKESPECIAL, PROVIDER + "$Source", "<init>", "()V", false));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(
        new FieldInsnNode(
            Opcodes.PUTFIELD, node.name, "fileOrigin", "Ldatadog/trace/api/ConfigOrigin;"));
    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
    code.add(new TypeInsnNode(Opcodes.NEW, config));
    code.add(new InsnNode(Opcodes.DUP));
    code.add(new InsnNode(Opcodes.ACONST_NULL));
    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
    code.add(
        new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "datadog/trace/api/ConfigOrigin",
            "name",
            "()Ljava/lang/String;",
            false));
    code.add(
        new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            RUNTIME,
            "stableConfig",
            "(Ljava/lang/String;)Ljava/util/Map;",
            false));
    code.add(
        new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            config,
            "<init>",
            "(Ljava/lang/String;Ljava/util/Map;)V",
            false));
    code.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "config", "L" + config + ";"));
    code.add(new InsnNode(Opcodes.RETURN));
    return 1;
  }

  /** Passes every returned value through a runtime method with the same type. */
  private static Patch beforeReturns(String runtimeMethod, String descriptor) {
    return (owner, method) -> {
      int applied = 0;
      for (AbstractInsnNode instruction : method.instructions.toArray()) {
        if (instruction.getOpcode() == Opcodes.ARETURN) {
          method.instructions.insertBefore(
              instruction,
              new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, runtimeMethod, descriptor, false));
          applied++;
        }
      }
      return applied;
    };
  }

  /** Returns early from a void method unless a runtime predicate has the given value. */
  private static Patch returnUnless(String runtimeMethod, boolean continueWhen) {
    return (owner, method) -> {
      InsnList guard = new InsnList();
      LabelNode proceed = new LabelNode();
      guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, runtimeMethod, "()Z", false));
      guard.add(new JumpInsnNode(continueWhen ? Opcodes.IFNE : Opcodes.IFEQ, proceed));
      guard.add(new InsnNode(Opcodes.RETURN));
      guard.add(proceed);
      guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
      method.instructions.insert(guard);
      return 1;
    };
  }

  /** Passes the result of each matching call through a runtime method with the same type. */
  private static Patch afterCalls(
      String callOwner, String callName, String runtimeMethod, String descriptor) {
    return (owner, method) -> {
      int applied = 0;
      for (AbstractInsnNode instruction : method.instructions.toArray()) {
        if (instruction instanceof MethodInsnNode
            && ((MethodInsnNode) instruction).owner.equals(callOwner)
            && ((MethodInsnNode) instruction).name.equals(callName)) {
          method.instructions.insert(
              instruction,
              new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, runtimeMethod, descriptor, false));
          applied++;
        }
      }
      return applied;
    };
  }

  /** Replaces each matching static call with a runtime method of the same signature. */
  private static Patch redirectCall(
      String callOwner, String callName, String descriptor, String runtimeMethod) {
    return (owner, method) -> {
      int applied = 0;
      for (AbstractInsnNode instruction : method.instructions) {
        if (instruction instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) instruction;
          if (call.owner.equals(callOwner)
              && call.name.equals(callName)
              && call.desc.equals(descriptor)) {
            call.owner = RUNTIME;
            call.name = runtimeMethod;
            applied++;
          }
        }
      }
      return applied;
    };
  }

  /** Workers get a numeric IPC host, so name resolution cannot pick a different interface. */
  private static int numericHost(ClassNode owner, MethodNode method) {
    int applied = 0;
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
          applied++;
        }
      }
    }
    return applied;
  }

  /** Relocates module names in the packed instrumenter index, checking the stock layout. */
  byte[] rewriteInstrumenterIndex(byte[] bytes) throws IOException {
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
      require(found.add(module), "Duplicate instrumenter module");
      writeName(out, relocate(module));
      out.writeShort(in.readUnsignedShort());
      int flags = in.readUnsignedByte();
      require((flags & ~3) == 0, "Unknown module flags");
      out.writeByte(flags);
      int members = in.readUnsignedByte();
      out.writeByte(members);
      // 255 means the module is its own single member.
      for (int member = 0; member < (members == 255 ? 1 : members); member++) {
        if (members != 255) {
          writeName(out, readName(in));
        }
        if ((flags & 1) != 0) {
          int overrides = in.readUnsignedByte();
          out.writeByte(overrides);
          for (int o = 0; o < overrides; o++) {
            writeName(out, readName(in));
            out.writeShort(in.readUnsignedShort());
          }
        }
      }
      actualTransformations += members == 255 ? 1 : members;
    }
    require(
        in.available() == 0 && actualTransformations == transformations,
        "Instrumenter index layout changed");
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    DataOutputStream header = new DataOutputStream(result);
    header.writeInt(modules);
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

  /** Feature roots such as {@code inst/}, read from the stock jar index. */
  private static List<String> featureRoots(JarFile jar) throws IOException {
    try (DataInputStream in = new DataInputStream(jar.getInputStream(jar.getEntry(STOCK_INDEX)))) {
      List<String> prefixes = new ArrayList<>();
      for (int i = in.readInt(); i > 0; i--) {
        prefixes.add(in.readUTF());
      }
      return prefixes;
    }
  }

  /** Runs the stock jar index generator over the relocated feature-root entries. */
  private static byte[] buildJarIndex(
      File stock, Map<String, byte[]> entries, List<String> featureRoots) throws Exception {
    Path directory = Files.createTempDirectory("observer-index");
    try {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        if (inFeatureRoot(entry.getKey(), featureRoots)) {
          Path file = directory.resolve(entry.getKey());
          Files.createDirectories(file.getParent());
          Files.write(file, entry.getValue());
        }
      }
      Path index = Files.createTempDirectory("observer-index-output");
      try (URLClassLoader loader = new URLClassLoader(new URL[] {stock.toURI().toURL()}, null)) {
        Method main =
            loader
                .loadClass("datadog.trace.bootstrap.AgentJarIndex$IndexGenerator")
                .getMethod("main", String[].class);
        main.setAccessible(true);
        main.invoke(null, (Object) new String[] {directory.toString(), index.toString()});
        return Files.readAllBytes(index.resolve(STOCK_INDEX));
      } finally {
        delete(index);
      }
    } finally {
      delete(directory);
    }
  }

  /** Reads the generated index with the stock reader and checks it finds every feature class. */
  private static void verifyJarIndex(
      File stock, byte[] index, Map<String, byte[]> entries, List<String> featureRoots)
      throws Exception {
    Path probe = Files.createTempFile("observer-index", ".jar");
    try {
      Map<String, byte[]> indexOnly = Collections.singletonMap(STOCK_INDEX, index);
      write(probe.toFile(), new Manifest(), indexOnly);
      try (URLClassLoader loader = new URLClassLoader(new URL[] {stock.toURI().toURL()}, null);
          JarFile jar = new JarFile(probe.toFile())) {
        Class<?> type = loader.loadClass("datadog.trace.bootstrap.AgentJarIndex");
        Object reader = type.getMethod("readIndex", JarFile.class).invoke(null, jar);
        require(reader != null, "Stock reader could not read the observer index");
        Method classEntryName = type.getMethod("classEntryName", String.class);
        for (String name : entries.keySet()) {
          if (name.endsWith(".classdata") && inFeatureRoot(name, featureRoots)) {
            String className =
                name.substring(name.indexOf('/') + 1, name.length() - ".classdata".length())
                    .replace('/', '.');
            require(
                name.equals(classEntryName.invoke(reader, className)),
                "Index does not resolve " + className);
          }
        }
      }
    } finally {
      Files.deleteIfExists(probe);
    }
  }

  private static boolean inFeatureRoot(String name, List<String> featureRoots) {
    for (String root : featureRoots) {
      if (name.startsWith(root)) {
        return true;
      }
    }
    return false;
  }

  /** Writes a sibling file and moves it into place, so a running JVM keeps its old jar. */
  private static void write(File output, Manifest manifest, Map<String, byte[]> entries)
      throws IOException {
    Path target = output.getAbsoluteFile().toPath();
    Files.createDirectories(target.getParent());
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

  private static void delete(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
        Files.delete(path);
      }
    }
  }

  private static byte[] lines(Set<String> values) {
    return String.join("\n", values).getBytes(StandardCharsets.UTF_8);
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
