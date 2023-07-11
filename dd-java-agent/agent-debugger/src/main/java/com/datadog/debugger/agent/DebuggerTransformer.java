package com.datadog.debugger.agent;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ExceptionHelper;
import datadog.trace.agent.tooling.AgentStrategies;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles transformations of loading classes matching the probe definitions provided by the
 * debugger configuration
 */
public class DebuggerTransformer implements ClassFileTransformer {
  private static final Logger log = LoggerFactory.getLogger(DebuggerTransformer.class);
  private static final String CANNOT_FIND_METHOD = "Cannot find method %s::%s";
  private static final String CANNOT_FIND_LINE = "No executable code was found at %s:L%s";
  private static final Pattern COMMA_PATTERN = Pattern.compile(",");

  private final Config config;
  private final TransformerDefinitionMatcher definitonMatcher;
  private final AllowListHelper allowListHelper;
  private final DenyListHelper denyListHelper;
  private final InstrumentationListener listener;
  private final boolean instrumentTheWorld;
  private final Set<String> excludeClasses = new HashSet<>();
  private final Trie excludeTrie = new Trie();
  private final Map<String, LogProbe> instrumentTheWorldProbes;

  public interface InstrumentationListener {
    void instrumentationResult(ProbeDefinition definition, InstrumentationResult result);
  }

  public DebuggerTransformer(
      Config config, Configuration configuration, InstrumentationListener listener) {
    this.config = config;
    this.definitonMatcher = new TransformerDefinitionMatcher(configuration);
    this.allowListHelper = new AllowListHelper(configuration.getAllowList());
    this.denyListHelper = new DenyListHelper(configuration.getDenyList());
    this.listener = listener;
    this.instrumentTheWorld = config.isDebuggerInstrumentTheWorld();
    if (this.instrumentTheWorld) {
      instrumentTheWorldProbes = new ConcurrentHashMap<>();
      readExcludeFiles(config.getDebuggerExcludeFiles());
    } else {
      instrumentTheWorldProbes = null;
    }
  }

  public DebuggerTransformer(Config config, Configuration configuration) {
    this(config, configuration, null);
  }

  private void readExcludeFiles(String commaSeparatedFileNames) {
    if (commaSeparatedFileNames == null) {
      return;
    }
    String[] fileNames = COMMA_PATTERN.split(commaSeparatedFileNames);
    for (String fileName : fileNames) {
      Path excludePath = Paths.get(fileName);
      if (!Files.exists(excludePath)) {
        log.warn("Cannot find exclude file: {}", excludePath);
        continue;
      }
      try {
        Files.lines(excludePath)
            .forEach(
                line -> {
                  if (line.startsWith("#")) {
                    return;
                  }
                  if (line.endsWith("*")) {
                    excludeTrie.insert(line.substring(0, line.length() - 1));
                  } else {
                    excludeClasses.add(line);
                  }
                });
      } catch (IOException ex) {
        log.warn("Error reading exclude file '{}' for Instrument-The-World: ", fileName, ex);
      }
    }
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String classFilePath,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (instrumentTheWorld) {
      return transformTheWorld(
          loader, classFilePath, classBeingRedefined, protectionDomain, classfileBuffer);
    }
    if (skipInstrumentation(loader, classFilePath)) {
      return null;
    }
    try {
      String fullyQualifiedClassName = classFilePath.replace('/', '.');
      List<ProbeDefinition> definitions =
          definitonMatcher.match(
              classBeingRedefined, classFilePath, fullyQualifiedClassName, classfileBuffer);
      if (definitions.isEmpty()) {
        return null;
      }
      log.debug("Matching definitions for class[{}]: {}", fullyQualifiedClassName, definitions);
      if (!instrumentationIsAllowed(fullyQualifiedClassName, definitions)) {
        return null;
      }
      Map<Where, List<ProbeDefinition>> defByLocation = mergeLocations(definitions);
      ClassNode classNode = parseClassFile(classFilePath, classfileBuffer);
      boolean transformed =
          performInstrumentation(loader, fullyQualifiedClassName, defByLocation, classNode);
      if (transformed) {
        return writeClassFile(loader, classFilePath, classNode);
      }
      // This is an info log because in case of SourceFile definition and multiple top-level
      // classes, type may match, but there is one classfile per top-level class so source file
      // will match, but not the classfile.
      // e.g. Main.java contains Main & TopLevel class, line numbers are in TopLevel class
      log.info(
          "type {} matched but no transformation for definitions: {}", classFilePath, definitions);
    } catch (Throwable ex) {
      log.warn("Cannot transform: ", ex);
    }
    return null;
  }

  private Map<Where, List<ProbeDefinition>> mergeLocations(List<ProbeDefinition> definitions) {
    Map<Where, List<ProbeDefinition>> mergedProbes = new HashMap<>();
    for (ProbeDefinition definition : definitions) {
      List<ProbeDefinition> instrumentationDefinitions =
          mergedProbes.computeIfAbsent(definition.getWhere(), key -> new ArrayList<>());
      instrumentationDefinitions.add(definition);
    }
    return mergedProbes;
  }

  private boolean skipInstrumentation(ClassLoader loader, String classFilePath) {
    if (definitonMatcher.isEmpty()) {
      log.warn("No debugger definitions present.");
      return true;
    }
    if (classFilePath == null) {
      // in case of anonymous classes
      return true;
    }
    return false;
  }

  private byte[] transformTheWorld(
      ClassLoader loader,
      String classFilePath,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    try {
      if (classFilePath == null) { // in case of anonymous classes
        return null;
      }
      if (loader == null) {
        // Skipping bootstrap classloader
        return null;
      }
      if (classFilePath.startsWith("com/datadog/debugger/")
          || classFilePath.startsWith("com/timgroup/statsd/")) {
        // Skipping classes that are used to capture and send snapshots
        return null;
      }
      if (isExcludedFromTransformation(classFilePath)) {
        return null;
      }
      URL location = null;
      if (protectionDomain != null) {
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource != null) {
          location = codeSource.getLocation();
        }
      }
      log.debug(
          "Parsing class '{}' {}B loaded from loader='{}' location={}",
          classFilePath,
          classfileBuffer.length,
          loader,
          location);
      ClassNode classNode = parseClassFile(classFilePath, classfileBuffer);
      List<ProbeDefinition> probes = new ArrayList<>();
      Set<String> methodNames = new HashSet<>();
      for (MethodNode methodNode : classNode.methods) {
        if (methodNames.add(methodNode.name)) {
          LogProbe probe =
              LogProbe.builder()
                  .probeId(UUID.randomUUID().toString(), 0)
                  .where(classNode.name, methodNode.name)
                  .captureSnapshot(true)
                  .build();
          probes.add(probe);
          instrumentTheWorldProbes.put(probe.getId(), probe);
        }
      }
      Map<Where, List<ProbeDefinition>> defByLocation = mergeLocations(probes);
      boolean transformed = performInstrumentation(loader, classFilePath, defByLocation, classNode);
      if (transformed) {
        return writeClassFile(loader, classFilePath, classNode);
      }
    } catch (Throwable ex) {
      log.warn("Cannot transform: ", ex);
      writeToInstrumentationLog(classFilePath);
    }
    return null;
  }

  private synchronized void writeToInstrumentationLog(String classFilePath) {
    try (FileWriter writer = new FileWriter("/tmp/debugger/instrumentation.log", true)) {
      writer.write(classFilePath);
      writer.write("\n");
    } catch (Exception ex) {
      log.warn("Cannot write to instrumentation.log", ex);
    }
  }

  public ProbeImplementation instrumentTheWorldResolver(String id, Class<?> callingClass) {
    if (instrumentTheWorldProbes == null) {
      return null;
    }
    return instrumentTheWorldProbes.get(id);
  }

  private boolean isExcludedFromTransformation(String classFilePath) {
    if (excludeClasses.contains(classFilePath)) {
      return true;
    }
    if (excludeTrie.hasMatchingPrefix(classFilePath)) {
      return true;
    }
    return false;
  }

  private boolean instrumentationIsAllowed(
      String fullyQualifiedClassName, List<ProbeDefinition> definitions) {
    if (denyListHelper.isDenied(fullyQualifiedClassName)) {
      log.info("Instrumentation denied for {}", fullyQualifiedClassName);
      InstrumentationResult result =
          InstrumentationResult.Factory.blocked(
              fullyQualifiedClassName,
              definitions,
              new DiagnosticMessage(
                  DiagnosticMessage.Kind.WARN,
                  "Instrumentation denied for " + fullyQualifiedClassName));
      notifyBlockedDefinitions(definitions, result);
      return false;
    }
    if (!allowListHelper.isAllowAll() && !allowListHelper.isAllowed(fullyQualifiedClassName)) {
      log.info("Instrumentation not allowed for {}", fullyQualifiedClassName);
      InstrumentationResult result =
          InstrumentationResult.Factory.blocked(
              fullyQualifiedClassName,
              definitions,
              new DiagnosticMessage(
                  DiagnosticMessage.Kind.WARN,
                  "Instrumentation not allowed for " + fullyQualifiedClassName));
      notifyBlockedDefinitions(definitions, result);
      return false;
    }
    return true;
  }

  private ClassNode parseClassFile(String classFilePath, byte[] classfileBuffer) {
    ClassReader reader = new ClassReader(classfileBuffer);
    dumpOriginalClassFile(classFilePath, classfileBuffer);
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.SKIP_FRAMES);
    return classNode;
  }

  private byte[] writeClassFile(ClassLoader loader, String classFilePath, ClassNode classNode) {
    if (classNode.version < Opcodes.V1_8) {
      // Class file version must be at least 1.8 (52)
      classNode.version = Opcodes.V1_8;
    }
    ClassWriter writer = new SafeClassWriter(loader);
    log.debug("Generating bytecode for class: {}", classFilePath.replace('/', '.'));
    try {
      classNode.accept(writer);
    } catch (Throwable t) {
      log.error("Cannot write classfile for class: {}", classFilePath, t);
    }
    byte[] data = writer.toByteArray();
    dumpInstrumentedClassFile(classFilePath, data);
    verifyByteCode(classFilePath, data);
    return data;
  }

  private void verifyByteCode(String classFilePath, byte[] classFile) {
    if (config.isDebuggerVerifyByteCode()) {
      StringWriter stringWriter = new StringWriter();
      PrintWriter printWriter = new PrintWriter(stringWriter);
      ClassReader classReader = new ClassReader(classFile);
      ClassNode classNode = new ClassNode();
      classReader.accept(
          new CheckClassAdapter(Opcodes.ASM7, classNode, false) {}, ClassReader.SKIP_DEBUG);
      List<MethodNode> methods = classNode.methods;
      for (MethodNode method : methods) {
        BasicVerifier verifier = new BasicVerifier();
        Analyzer<BasicValue> analyzer = new Analyzer<>(verifier);
        try {
          analyzer.analyze(classNode.name, method);
        } catch (AnalyzerException e) {
          printWriter.printf(
              "Error analyzing method '%s.%s%s':%n", classNode.name, method.name, method.desc);
          e.printStackTrace(printWriter);
        }
      }
      printWriter.flush();
      String result = stringWriter.toString();
      if (!result.isEmpty()) {
        log.warn("Verification of instrumented class {} failed", classFilePath);
        log.debug("Verify result: {}", stringWriter);
        throw new RuntimeException("Generated bydecode is invalid for " + classFilePath);
      }
    }
  }

  private boolean performInstrumentation(
      ClassLoader loader,
      String fullyQualifiedClassName,
      Map<Where, List<ProbeDefinition>> definitionByLocation,
      ClassNode classNode) {
    boolean transformed = false;
    // FIXME build a map also for methods to optimize the matching, currently O(probes*methods)
    for (Map.Entry<Where, List<ProbeDefinition>> entry : definitionByLocation.entrySet()) {
      Where where = entry.getKey();
      String methodName = where.getMethodName();
      String[] lines = where.getLines();
      List<MethodNode> methodNodes;
      if (methodName == null && lines != null) {
        MethodNode methodNode = matchSourceFile(classNode, where);
        methodNodes = methodNode != null ? singletonList(methodNode) : Collections.emptyList();
      } else {
        methodNodes = matchMethodDescription(classNode, where);
      }
      List<ProbeDefinition> definitions = entry.getValue();
      if (methodNodes.isEmpty()) {
        reportLocationNotFound(definitions, classNode.name, methodName);
        continue;
      }
      for (MethodNode methodNode : methodNodes) {
        if (log.isDebugEnabled()) {
          List<String> probeIds =
              definitions.stream().map(ProbeDefinition::getId).collect(toList());
          log.debug(
              "Instrumenting method: {}.{}{} for probe ids: {}",
              fullyQualifiedClassName,
              methodNode.name,
              methodNode.desc,
              probeIds);
        }
        InstrumentationResult result =
            applyInstrumentation(loader, classNode, definitions, methodNode);
        transformed |= result.isInstalled();
        for (ProbeDefinition definition : definitions) {
          definition.buildLocation(result);
          if (listener != null) {
            listener.instrumentationResult(definition, result);
          }
          List<DiagnosticMessage> diagnosticMessages =
              result.getDiagnostics().get(definition.getProbeId());
          if (!result.getDiagnostics().isEmpty()) {
            DebuggerAgent.getSink().addDiagnostics(definition.getProbeId(), diagnosticMessages);
          }
        }
      }
    }
    return transformed;
  }

  private void reportLocationNotFound(
      List<ProbeDefinition> definitions, String className, String methodName) {
    String format;
    String location;
    if (methodName != null) {
      format = CANNOT_FIND_METHOD;
      location = methodName;
    } else {
      // This is a line probe, so we don't report line not found because the line may be found later
      // on a separate class files because probe was set on an inner/top-level class
      return;
    }
    String msg = String.format(format, className, location);
    DiagnosticMessage diagnosticMessage = new DiagnosticMessage(DiagnosticMessage.Kind.ERROR, msg);
    for (ProbeDefinition definition : definitions) {
      DebuggerAgent.getSink()
          .addDiagnostics(definition.getProbeId(), singletonList(diagnosticMessage));
      log.debug("{} for definition: {}", msg, definition);
    }
  }

  private void notifyBlockedDefinitions(
      List<ProbeDefinition> definitions, InstrumentationResult result) {
    if (listener != null) {
      for (ProbeDefinition definition : definitions) {
        listener.instrumentationResult(definition, result);
      }
    }
  }

  private InstrumentationResult applyInstrumentation(
      ClassLoader classLoader,
      ClassNode classNode,
      List<ProbeDefinition> definitions,
      MethodNode methodNode) {
    Map<ProbeId, List<DiagnosticMessage>> diagnostics = new HashMap<>();
    definitions.forEach(
        probeDefinition -> diagnostics.put(probeDefinition.getProbeId(), new ArrayList<>()));
    InstrumentationResult.Status status =
        preCheckInstrumentation(diagnostics, classLoader, methodNode);
    if (status != InstrumentationResult.Status.ERROR) {
      try {
        List<ProbeDefinition> logProbes = new ArrayList<>();
        for (ProbeDefinition definition : definitions) {
          if (definition instanceof LogProbe) {
            logProbes.add(definition);
          } else {
            List<DiagnosticMessage> probeDiagnostics = diagnostics.get(definition.getProbeId());
            definition.instrument(classLoader, classNode, methodNode, probeDiagnostics);
          }
        }
        if (logProbes.size() > 0) {
          List<String> probesIds = logProbes.stream().map(ProbeDefinition::getId).collect(toList());
          List<DiagnosticMessage> probeDiagnostics = diagnostics.get(logProbes.get(0).getProbeId());
          logProbes
              .get(0)
              .instrument(classLoader, classNode, methodNode, probeDiagnostics, probesIds);
        }
      } catch (Throwable t) {
        log.warn("Exception during instrumentation: ", t);
        status = InstrumentationResult.Status.ERROR;
        addDiagnosticForAllProbes(
            new DiagnosticMessage(DiagnosticMessage.Kind.ERROR, t), diagnostics);
      }
    }
    return new InstrumentationResult(status, diagnostics, classNode, methodNode);
  }

  private InstrumentationResult.Status preCheckInstrumentation(
      Map<ProbeId, List<DiagnosticMessage>> diagnostics,
      ClassLoader classLoader,
      MethodNode methodNode) {
    if ((methodNode.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
      if (!instrumentTheWorld) {
        addDiagnosticForAllProbes(
            new DiagnosticMessage(
                DiagnosticMessage.Kind.ERROR, "Cannot instrument an abstract or native method"),
            diagnostics);
      }
      return InstrumentationResult.Status.ERROR;
    }
    if (classLoader != null
        && classLoader.getClass().getTypeName().equals("sun.reflect.DelegatingClassLoader")) {
      // This classloader is used when using reflection. This is a special classloader known
      // by the JVM with special behavior. it cannot load other classes inside it (e.i. no
      // delegation to parent classloader).
      // Trying to instrument a method from this classloader result in an Exception/Error:
      // java.lang.NoClassDefFoundError: sun/reflect/GeneratedMethodAccessor<n>
      // Caused by: java.lang.ClassNotFoundException: sun.reflect.GeneratedMethodAccessor<n>
      // source:
      // https://github.com/openjdk/jdk/blob/1581e3faa06358f192799b3a89718028c7f6a24b/src/hotspot/share/classfile/javaClasses.cpp#L4392-L4412
      if (!instrumentTheWorld) {
        addDiagnosticForAllProbes(
            new DiagnosticMessage(
                DiagnosticMessage.Kind.ERROR, "Cannot instrument class in DelegatingClassLoader"),
            diagnostics);
      }
      return InstrumentationResult.Status.ERROR;
    }
    return InstrumentationResult.Status.INSTALLED;
  }

  private static void addDiagnosticForAllProbes(
      DiagnosticMessage diagnosticMessage, Map<ProbeId, List<DiagnosticMessage>> diagnostics) {
    diagnostics.forEach((probeId, diagnosticMessages) -> diagnosticMessages.add(diagnosticMessage));
  }

  private List<MethodNode> matchMethodDescription(ClassNode classNode, Where where) {
    List<MethodNode> result = new ArrayList<>();
    try {
      for (MethodNode methodNode : classNode.methods) {
        if (where.isMethodMatching(methodNode.name, methodNode.desc)) {
          result.add(methodNode);
        }
      }
    } catch (Exception ex) {
      log.warn("Cannot match method: {}", ex.toString());
    }
    return result;
  }

  private MethodNode matchSourceFile(ClassNode classNode, Where where) {
    Where.SourceLine[] lines = where.getSourceLines();
    if (lines == null || lines.length == 0) {
      return null;
    }
    Where.SourceLine sourceLine = lines[0]; // assume only 1 range
    int matchingLine = sourceLine.getFrom();
    for (MethodNode methodNode : classNode.methods) {
      AbstractInsnNode currentInsn = methodNode.instructions.getFirst();
      while (currentInsn != null) {
        if (currentInsn instanceof LineNumberNode) {
          LineNumberNode lineNode = (LineNumberNode) currentInsn;
          if (lineNode.line == matchingLine) {
            log.debug("Found lineNode {} method: {}", matchingLine, methodNode.name);
            return methodNode;
          }
        }
        currentInsn = currentInsn.getNext();
      }
    }
    log.debug("Cannot find line: {} in class {}", matchingLine, classNode.name);
    return null;
  }

  private void dumpInstrumentedClassFile(String className, byte[] data) {
    if (config.isDebuggerClassFileDumpEnabled()) {
      log.debug("Generated bytecode len: {}", data.length);
      Path classFilePath = dumpClassFile(className, data);
      if (classFilePath != null) {
        log.debug("Instrumented class saved as: {}", classFilePath.toString());
      }
    }
  }

  private void dumpOriginalClassFile(String className, byte[] classfileBuffer) {
    if (config.isDebuggerClassFileDumpEnabled()) {
      Path classFilePath = dumpClassFile(className + "_orig", classfileBuffer);
      if (classFilePath != null) {
        log.debug("Original class saved as: {}", classFilePath.toString());
      }
    }
  }

  private static Path dumpClassFile(String className, byte[] classfileBuffer) {
    try {
      Path classFilePath = Paths.get("/tmp/debugger/" + className + ".class");
      Files.createDirectories(classFilePath.getParent());
      Files.write(classFilePath, classfileBuffer, StandardOpenOption.CREATE);
      return classFilePath;
    } catch (IOException e) {
      log.error("", e);
      return null;
    }
  }

  static class SafeClassWriter extends ClassWriter {
    private final ClassLoader classLoader;

    public SafeClassWriter(ClassLoader classLoader) {
      super(ClassWriter.COMPUTE_FRAMES);
      this.classLoader = classLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
      // We cannot use ASM's getCommonSuperClass because it tries to load super class with
      // ClassLoader which in some circumstances can lead to
      // java.lang.LinkageError: loader (instance of  sun/misc/Launcher$AppClassLoader): attempted
      // duplicate class definition for name: "okhttp3/RealCall"
      // for more info see:
      // https://stackoverflow.com/questions/69563714/linkageerror-attempted-duplicate-class-definition-when-dynamically-instrument
      TypePool tpTargetClassLoader =
          new TypePool.Default.WithLazyResolution(
              TypePool.CacheProvider.Simple.withObjectType(),
              AgentStrategies.locationStrategy().classFileLocator(classLoader, null),
              TypePool.Default.ReaderMode.FAST);
      // Introduced the java agent DataDog classloader for resolving types introduced by other
      // Datadog instrumentation (Tracing, AppSec, Profiling, ...)
      // Here we assume that the current class is loaded in DataDog classloader
      TypePool tpDatadogClassLoader =
          new TypePool.Default.WithLazyResolution(
              TypePool.CacheProvider.Simple.withObjectType(),
              AgentStrategies.locationStrategy()
                  .classFileLocator(getClass().getClassLoader(), null),
              TypePool.Default.ReaderMode.FAST,
              tpTargetClassLoader);

      try {
        TypeDescription td1 = tpDatadogClassLoader.describe(type1.replace('/', '.')).resolve();
        TypeDescription td2 = tpDatadogClassLoader.describe(type2.replace('/', '.')).resolve();
        TypeDescription common = null;
        if (td1.isAssignableFrom(td2)) {
          common = td1;
        } else if (td2.isAssignableFrom(td1)) {
          common = td2;
        } else {
          if (td1.isInterface() || td2.isInterface()) {
            common = tpDatadogClassLoader.describe("java.lang.Object").resolve();
          } else {
            common = td1;
            do {
              common = common.getSuperClass().asErasure();
            } while (!common.isAssignableFrom(td2));
          }
        }
        return common.getInternalName();
      } catch (Exception ex) {
        ExceptionHelper.logException(log, ex, "getCommonSuperClass failed: ");
        return tpDatadogClassLoader.describe("java.lang.Object").resolve().getInternalName();
      }
    }
  }
}
