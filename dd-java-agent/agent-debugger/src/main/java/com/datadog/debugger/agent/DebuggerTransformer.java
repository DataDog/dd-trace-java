package com.datadog.debugger.agent;

import static com.datadog.debugger.instrumentation.ASMHelper.getLineNumbers;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.probe.ForceMethodInstrumentation;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.SnapshotSink;
import com.datadog.debugger.sink.SymbolSink;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.ClassFileLines;
import com.datadog.debugger.util.DebuggerMetrics;
import datadog.environment.SystemProperties;
import datadog.trace.agent.tooling.AgentStrategies;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.util.RandomUtils;
import datadog.trace.util.Strings;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.ClassNode;
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
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerTransformer.class);
  private static final String CANNOT_FIND_METHOD = "Cannot find method %s::%s%s";
  private static final String INSTRUMENTATION_FAILS = "Instrumentation fails for %s";
  private static final String CANNOT_FIND_LINE = "No executable code was found at %s:L%s";
  private static final Pattern COMMA_PATTERN = Pattern.compile(",");
  private static final List<Class<?>> PROBE_ORDER =
      Arrays.asList(
          TriggerProbe.class,
          MetricProbe.class,
          LogProbe.class,
          SpanDecorationProbe.class,
          SpanProbe.class);
  private static final String JAVA_IO_TMPDIR = "java.io.tmpdir";

  public static Path DUMP_PATH = Paths.get(SystemProperties.get(JAVA_IO_TMPDIR), "debugger");

  private final Config config;
  private final TransformerDefinitionMatcher definitionMatcher;
  private final AllowListHelper allowListHelper;
  private final DenyListHelper denyListHelper;
  private final InstrumentationListener listener;
  private final ProbeMetadata probeMetadata;
  private final DebuggerSink debuggerSink;
  private final boolean instrumentTheWorld;
  private final Set<String> excludeClasses;
  private final Set<String> excludeMethods;
  private final Trie excludeTrie;
  private final Set<String> includeClasses;
  private final Set<String> includeMethods;
  private final Trie includeTrie;
  private final BiConsumer<MethodInfo, List<ProbeDefinition>> probeCreator;

  public interface InstrumentationListener {
    void instrumentationResult(ProbeDefinition definition, InstrumentationResult result);
  }

  public DebuggerTransformer(
      Config config,
      Configuration configuration,
      InstrumentationListener listener,
      ProbeMetadata probeMetadata,
      DebuggerSink debuggerSink) {
    this.config = config;
    this.definitionMatcher = new TransformerDefinitionMatcher(configuration);
    this.allowListHelper = new AllowListHelper(configuration.getAllowList());
    this.denyListHelper = new DenyListHelper(configuration.getDenyList());
    this.listener = listener;
    this.probeMetadata = probeMetadata;
    this.debuggerSink = debuggerSink;
    String itwType = config.getDynamicInstrumentationInstrumentTheWorld();
    this.instrumentTheWorld = itwType != null;
    if (this.instrumentTheWorld) {
      excludeTrie = new Trie();
      excludeClasses = new HashSet<>();
      excludeMethods = new HashSet<>();
      includeTrie = new Trie();
      includeClasses = new HashSet<>();
      includeMethods = new HashSet<>();
      processITWFiles(
          config.getDynamicInstrumentationExcludeFiles(),
          excludeTrie,
          excludeClasses,
          excludeMethods);
      processITWFiles(
          config.getDynamicInstrumentationIncludeFiles(),
          includeTrie,
          includeClasses,
          includeMethods);
      if (itwType.equals("method")) {
        probeCreator = this::createMethodProbe;
      } else if (itwType.equals("line")) {
        probeCreator = this::createLineProbes;
      } else {
        LOGGER.warn(
            "Invalid value for 'dd.debugger.instrument-the-world' property: {}. "
                + "Valid values are 'method' or 'line'.",
            itwType);
        probeCreator = null;
      }
    } else {
      excludeTrie = null;
      excludeClasses = null;
      excludeMethods = null;
      includeTrie = null;
      includeClasses = null;
      includeMethods = null;
      probeCreator = null;
    }
  }

  // Used only for tests
  public DebuggerTransformer(
      Config config, ProbeMetadata probeMetadata, Configuration configuration) {
    this(
        config,
        configuration,
        null,
        probeMetadata,
        new DebuggerSink(
            config,
            "",
            DebuggerMetrics.getInstance(config),
            new ProbeStatusSink(config, config.getFinalDebuggerSnapshotUrl(), false),
            new SnapshotSink(
                config,
                "",
                new BatchUploader(
                    "Snapshots",
                    config,
                    config.getFinalDebuggerSnapshotUrl(),
                    SnapshotSink.RETRY_POLICY),
                new BatchUploader(
                    "Logs",
                    config,
                    config.getFinalDebuggerSnapshotUrl(),
                    SnapshotSink.RETRY_POLICY)),
            new SymbolSink(config)));
  }

  private void processITWFiles(
      String commaSeparatedFileNames, Trie prefixes, Set<String> classes, Set<String> methods) {
    if (commaSeparatedFileNames == null) {
      return;
    }
    String[] fileNames = COMMA_PATTERN.split(commaSeparatedFileNames);
    for (String fileName : fileNames) {
      Path excludePath = Paths.get(fileName);
      if (!Files.exists(excludePath)) {
        LOGGER.warn("Cannot find exclude file: {}", excludePath);
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
                    prefixes.insert(line.substring(0, line.length() - 1));
                    return;
                  }
                  if (line.contains("::")) {
                    methods.add(line);
                    return;
                  }
                  classes.add(line);
                });
      } catch (IOException ex) {
        LOGGER.warn("Error reading exclude file '{}' for Instrument-The-World: ", fileName, ex);
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
      return transformTheWorld(loader, classFilePath, protectionDomain, classfileBuffer);
    }
    if (skipInstrumentation(loader, classFilePath)) {
      return null;
    }
    List<ProbeDefinition> definitions = Collections.emptyList();
    String fullyQualifiedClassName = classFilePath.replace('/', '.');
    try {
      definitions =
          definitionMatcher.match(
              classBeingRedefined, classFilePath, fullyQualifiedClassName, classfileBuffer);
      if (definitions.isEmpty()) {
        return null;
      }
      LOGGER.debug("Matching definitions for class[{}]: {}", fullyQualifiedClassName, definitions);
      if (!instrumentationIsAllowed(fullyQualifiedClassName, definitions)) {
        return null;
      }
      ClassNode classNode = parseClassFile(classFilePath, classfileBuffer);
      boolean transformed =
          performInstrumentation(loader, fullyQualifiedClassName, definitions, classNode);
      if (transformed) {
        return writeClassFile(definitions, loader, classFilePath, classNode);
      }
      // We are logging this because in case of SourceFile definition and multiple top-level
      // classes, type may match, but there is one classfile per top-level class so source file
      // will match, but not the classfile.
      // e.g. Main.java contains Main & TopLevel class, line numbers are in TopLevel class
      LOGGER.debug(
          "type {} matched but no transformation for definitions: {}", classFilePath, definitions);
    } catch (Throwable ex) {
      LOGGER.warn("Cannot transform: ", ex);
      reportInstrumentationFails(definitions, fullyQualifiedClassName);
    }
    return null;
  }

  private boolean skipInstrumentation(ClassLoader loader, String classFilePath) {
    if (definitionMatcher.isEmpty()) {
      LOGGER.debug("No debugger definitions present.");
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
      if (isExcludedFromTransformation(classFilePath)) {
        return null;
      }
      if (!isIncludedForTransformation(classFilePath)) {
        return null;
      }
      URL location = null;
      if (protectionDomain != null) {
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource != null) {
          location = codeSource.getLocation();
        }
      }
      LOGGER.debug(
          "Parsing class '{}' {}B loaded from loader='{}' location={}",
          classFilePath,
          classfileBuffer.length,
          loader,
          location);
      ClassNode classNode = parseClassFile(classFilePath, classfileBuffer);
      if (isClassLoaderRelated(classNode)) {
        // Skip ClassLoader classes
        LOGGER.debug("Skipping ClassLoader class: {}", classFilePath);
        excludeClasses.add(classFilePath);
        return null;
      }
      List<ProbeDefinition> probes = new ArrayList<>();
      Set<String> methodNames = new HashSet<>();
      ClassFileLines classFileLines = new ClassFileLines(classNode);
      for (MethodNode methodNode : classNode.methods) {
        if (isMethodIncludedForTransformation(methodNode, classNode, methodNames)) {
          MethodInfo methodInfo = new MethodInfo(loader, classNode, methodNode, classFileLines);
          probeCreator.accept(methodInfo, probes);
        }
      }
      boolean transformed = performInstrumentation(loader, classFilePath, probes, classNode);
      if (transformed) {
        return writeClassFile(probes, loader, classFilePath, classNode);
      } else {
        LOGGER.debug("Class not transformed: {}", classFilePath);
      }
    } catch (Throwable ex) {
      LOGGER.warn("Cannot transform: ", ex);
      writeToInstrumentationLog(classFilePath);
    }
    return null;
  }

  private boolean isMethodIncludedForTransformation(
      MethodNode methodNode, ClassNode classNode, Set<String> methodNames) {
    if (methodNode.name.equals("<clinit>")) {
      // skip static class initializer
      return false;
    }
    String fqnMethod = classNode.name + "::" + methodNode.name;
    if (excludeMethods.contains(fqnMethod)) {
      LOGGER.debug("Skipping method: {}", fqnMethod);
      return false;
    }
    return methodNames.add(methodNode.name);
  }

  private void createMethodProbe(MethodInfo methodInfo, List<ProbeDefinition> probes) {
    LogProbe probe =
        LogProbe.builder()
            .probeId(RandomUtils.randomUUID().toString(), 0)
            .where(methodInfo.getClassNode().name, methodInfo.getMethodNode().name)
            .captureSnapshot(false)
            .build();
    probes.add(probe);
  }

  private void createLineProbes(MethodInfo methodInfo, List<ProbeDefinition> probes) {
    if (methodInfo.getMethodName().equals("<init>")) {
      // skip constructor for now to avoid dealing with code before super calls
      return;
    }
    if ((methodInfo.getMethodNode().access & Opcodes.ACC_SYNTHETIC) != 0) {
      // skip synthetic methods
      return;
    }
    List<Integer> lineNumbers = getLineNumbers(methodInfo.getMethodNode());
    for (Integer lineNumber : lineNumbers) {
      LogProbe probe =
          LogProbe.builder()
              .probeId(RandomUtils.randomUUID().toString(), 0)
              .where(methodInfo.getSourceFileName(), lineNumber)
              .captureSnapshot(false)
              .build();
      probes.add(probe);
    }
  }

  private boolean isClassLoaderRelated(ClassNode classNode) {
    return classNode.superName.equals("java/lang/ClassLoader")
        || classNode.superName.equals("java/net/URLClassLoader")
        || excludeClasses.contains(classNode.superName);
  }

  private synchronized void writeToInstrumentationLog(String classFilePath) {
    try (FileWriter writer = new FileWriter("/tmp/debugger/instrumentation.log", true)) {
      writer.write(classFilePath);
      writer.write("\n");
    } catch (Exception ex) {
      LOGGER.warn("Cannot write to instrumentation.log", ex);
    }
  }

  public ProbeImplementation instrumentTheWorldResolver(int probeIndex) {
    return probeMetadata.getProbe(probeIndex);
  }

  private boolean isExcludedFromTransformation(String classFilePath) {
    if (classFilePath.startsWith("com/datadog/debugger/")
        || classFilePath.startsWith("com/timgroup/statsd/")) {
      // Skipping classes that are used to capture and send snapshots
      return true;
    }
    if (excludeClasses.contains(classFilePath)) {
      return true;
    }
    if (excludeTrie.hasMatchingPrefix(classFilePath)) {
      return true;
    }
    return false;
  }

  private boolean isIncludedForTransformation(String classFilePath) {
    if (includeClasses.contains(classFilePath)) {
      return true;
    }
    if (includeTrie.hasMatchingPrefix(classFilePath)) {
      return true;
    }
    return false;
  }

  private boolean instrumentationIsAllowed(
      String fullyQualifiedClassName, List<ProbeDefinition> definitions) {
    if (denyListHelper.isDenied(fullyQualifiedClassName)) {
      LOGGER.debug("Instrumentation denied for {}", fullyQualifiedClassName);
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
      LOGGER.debug("Instrumentation not allowed for {}", fullyQualifiedClassName);
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

  private byte[] writeClassFile(
      List<ProbeDefinition> definitions,
      ClassLoader loader,
      String classFilePath,
      ClassNode classNode) {
    if ((classNode.version & 0xFF) < Opcodes.V1_8) {
      // Class file version must be at least 1.8 (52)
      classNode.version = Opcodes.V1_8;
    }
    ClassWriter writer = new SafeClassWriter(loader);
    ClassVisitor visitor = new JsrInliningClassVisitor(writer);
    LOGGER.debug("Generating bytecode for class: {}", Strings.getClassName(classFilePath));
    try {
      classNode.accept(visitor);
    } catch (Throwable t) {
      LOGGER.error("Cannot write classfile for class: {} Exception: ", classFilePath, t);
      reportInstrumentationFails(definitions, Strings.getClassName(classFilePath));
      return null;
    }
    byte[] data = writer.toByteArray();
    dumpInstrumentedClassFile(classFilePath, data);
    verifyByteCode(classFilePath, data);
    return data;
  }

  private void verifyByteCode(String classFilePath, byte[] classFile) {
    if (!config.isDynamicInstrumentationVerifyByteCode()) {
      return;
    }
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    ClassReader classReader = new ClassReader(classFile);
    ClassNode classNode = new ClassNode();
    classReader.accept(
        new CheckClassAdapter(Opcodes.ASM9, classNode, false) {}, ClassReader.SKIP_DEBUG);
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
      LOGGER.warn("Verification of instrumented class {} failed", classFilePath);
      LOGGER.debug("Verify result: {}", stringWriter);
      throw new RuntimeException("Generated bytecode is invalid for " + classFilePath);
    }
  }

  private boolean performInstrumentation(
      ClassLoader loader,
      String fullyQualifiedClassName,
      List<ProbeDefinition> definitions,
      ClassNode classNode) {
    boolean transformed = false;
    ClassFileLines classFileLines = new ClassFileLines(classNode);
    Set<ProbeDefinition> remainingDefinitions = new HashSet<>(definitions);
    for (MethodNode methodNode : classNode.methods) {
      List<ProbeDefinition> matchingDefs = new ArrayList<>();
      for (ProbeDefinition definition : definitions) {
        Where.MethodMatching methodMatching =
            definition.getWhere().isMethodMatching(methodNode, classFileLines);
        if (remainingDefinitions.contains(definition)) {
          if (methodMatching == Where.MethodMatching.MATCH) {
            // method matches, add into collection of definitions to instrument
            matchingDefs.add(definition);
          }
          if (methodMatching == Where.MethodMatching.MATCH
              || methodMatching == Where.MethodMatching.SKIP)
            // match or need to skip instrumentation (because bridge) remove from remaining
            // definitions to avoid reporting error
            remainingDefinitions.remove(definition);
        }
      }
      if (matchingDefs.isEmpty()) {
        continue;
      }
      if (LOGGER.isDebugEnabled()) {
        List<String> probeIds = matchingDefs.stream().map(ProbeDefinition::getId).collect(toList());
        LOGGER.debug(
            "Instrumenting method: {}.{}{} for probe ids: {}",
            fullyQualifiedClassName,
            methodNode.name,
            methodNode.desc,
            probeIds);
      }
      MethodInfo methodInfo = new MethodInfo(loader, classNode, methodNode, classFileLines);
      InstrumentationResult result = applyInstrumentation(methodInfo, matchingDefs);
      transformed |= result.isInstalled();
      handleInstrumentationResult(matchingDefs, result);
    }
    for (ProbeDefinition definition : remainingDefinitions) {
      reportLocationNotFound(definition, classNode.name, definition.getWhere().getMethodName());
    }
    return transformed;
  }

  private void handleInstrumentationResult(
      List<ProbeDefinition> definitions, InstrumentationResult result) {
    for (ProbeDefinition definition : definitions) {
      if (listener != null) {
        listener.instrumentationResult(definition, result);
      }
      if (definition instanceof ExceptionProbe) {
        // do not report diagnostics for exception probes
        continue;
      }
      List<DiagnosticMessage> diagnosticMessages =
          result.getDiagnostics().get(definition.getProbeId());
      if (!result.getDiagnostics().isEmpty()) {
        addDiagnostics(definition, diagnosticMessages);
      }
      if (result.isInstalled()) {
        debuggerSink.addInstalled(definition.getProbeId());
      } else if (result.isBlocked()) {
        debuggerSink.addBlocked(definition.getProbeId());
      }
    }
  }

  private void reportLocationNotFound(
      ProbeDefinition definition, String className, String methodName) {
    if (methodName != null) {
      String signature = definition.getWhere().getSignature();
      signature = signature == null ? "" : signature;
      String msg = String.format(CANNOT_FIND_METHOD, className, methodName, signature);
      reportErrorForAllProbes(singletonList(definition), msg);
      return;
    }
    // This is a line probe, so we don't report line not found because the line may be found later
    // on a separate class files because probe was set on an inner/top-level class
  }

  private void reportInstrumentationFails(List<ProbeDefinition> definitions, String className) {
    String msg = String.format(INSTRUMENTATION_FAILS, className);
    reportErrorForAllProbes(definitions, msg);
  }

  private void reportErrorForAllProbes(List<ProbeDefinition> definitions, String msg) {
    DiagnosticMessage diagnosticMessage = new DiagnosticMessage(DiagnosticMessage.Kind.ERROR, msg);
    for (ProbeDefinition definition : definitions) {
      addDiagnostics(definition, singletonList(diagnosticMessage));
    }
  }

  private void addDiagnostics(
      ProbeDefinition definition, List<DiagnosticMessage> diagnosticMessages) {
    debuggerSink.addDiagnostics(definition.getProbeId(), diagnosticMessages);
    LOGGER.debug("Diagnostic messages for definition[{}]: {}", definition, diagnosticMessages);
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
      MethodInfo methodInfo, List<ProbeDefinition> definitions) {
    Map<ProbeId, List<DiagnosticMessage>> diagnostics = new HashMap<>();
    definitions.forEach(
        probeDefinition -> {
          probeDefinition.buildLocation(methodInfo);
          diagnostics.put(probeDefinition.getProbeId(), new ArrayList<>());
        });
    InstrumentationResult.Status status = preCheckInstrumentation(diagnostics, methodInfo);
    if (status != InstrumentationResult.Status.ERROR) {
      try {
        List<ToInstrumentInfo> toInstruments =
            filterAndSortDefinitions(definitions, methodInfo.getClassFileLines());
        for (ToInstrumentInfo toInstrumentInfo : toInstruments) {
          ProbeDefinition definition = toInstrumentInfo.definition;
          List<DiagnosticMessage> probeDiagnostics = diagnostics.get(definition.getProbeId());
          status =
              definition.instrument(methodInfo, probeDiagnostics, toInstrumentInfo.probeIndices);
        }
      } catch (Throwable t) {
        LOGGER.warn("Exception during instrumentation: ", t);
        status = InstrumentationResult.Status.ERROR;
        addDiagnosticForAllProbes(
            new DiagnosticMessage(DiagnosticMessage.Kind.ERROR, t), diagnostics);
      }
    }
    return new InstrumentationResult(status, diagnostics, methodInfo);
  }

  static class ToInstrumentInfo {
    final ProbeDefinition definition;
    final List<Integer> probeIndices;

    ToInstrumentInfo(ProbeDefinition definition, List<Integer> probeIndices) {
      this.definition = definition;
      this.probeIndices = probeIndices;
    }
  }

  private static boolean isCapturedContextProbe(ProbeDefinition definition) {
    return definition instanceof LogProbe
        || definition instanceof SpanDecorationProbe
        || definition instanceof TriggerProbe;
  }

  private List<ToInstrumentInfo> filterAndSortDefinitions(
      List<ProbeDefinition> definitions, ClassFileLines classFileLines) {
    List<ToInstrumentInfo> toInstrument = new ArrayList<>();
    List<ProbeDefinition> capturedContextProbes = new ArrayList<>();
    Map<Where, List<ProbeDefinition>> capturedContextLineProbes = new HashMap<>();
    boolean addedExceptionProbe = false;
    for (ProbeDefinition definition : definitions) {
      // Log and span decoration probe shared the same instrumentor: CaptureContextInstrumentor
      // and therefore need to be instrumented once
      // note: exception probes are log probes and are handled the same way
      if (!config.isDistributedDebuggerEnabled() && definition instanceof TriggerProbe) {
        LOGGER.debug(
            "The distributed debugger feature is disabled. Trigger probes will not be installed.");
      } else if (isCapturedContextProbe(definition)) {
        if (definition.isLineProbe()) {
          capturedContextLineProbes
              .computeIfAbsent(definition.getWhere(), key -> new ArrayList<>())
              .add(definition);
        } else {
          if (definition instanceof ExceptionProbe) {
            if (addedExceptionProbe) {
              continue;
            }
            // only add one exception probe to the list of probes to instrument
            // to have only one instance (1 probeId) of exception probe to handle all exceptions
            addedExceptionProbe = true;
          }
          capturedContextProbes.add(definition);
        }
      } else {
        int idx = probeMetadata.addProbe(definition);
        toInstrument.add(new ToInstrumentInfo(definition, singletonList(idx)));
      }
    }
    processCapturedContextLineProbes(capturedContextLineProbes, probeMetadata, toInstrument);
    processCapturedContextMethodProbes(
        classFileLines, capturedContextProbes, probeMetadata, toInstrument);
    // ordering: metric < log < span decoration < span
    toInstrument.sort(
        (info1, info2) -> {
          int idx1 = PROBE_ORDER.indexOf(info1.definition.getClass());
          int idx2 = PROBE_ORDER.indexOf(info2.definition.getClass());
          return Integer.compare(idx1, idx2);
        });
    return toInstrument;
  }

  private static void processCapturedContextMethodProbes(
      ClassFileLines classFileLines,
      List<ProbeDefinition> capturedContextProbes,
      ProbeMetadata probeMetadata,
      List<ToInstrumentInfo> toInstrument) {
    if (capturedContextProbes.isEmpty()) {
      return;
    }
    List<Integer> probeIndices = new ArrayList<>();
    for (ProbeDefinition definition : capturedContextProbes) {
      probeIndices.add(probeMetadata.addProbe(definition));
    }
    ProbeDefinition referenceDefinition =
        selectReferenceDefinition(capturedContextProbes, classFileLines);
    toInstrument.add(new ToInstrumentInfo(referenceDefinition, probeIndices));
  }

  private static void processCapturedContextLineProbes(
      Map<Where, List<ProbeDefinition>> lineProbes,
      ProbeMetadata probeMetadata,
      List<ToInstrumentInfo> toInstrument) {
    for (Map.Entry<Where, List<ProbeDefinition>> entry : lineProbes.entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      List<Integer> probeIndices = new ArrayList<>();
      for (ProbeDefinition definition : entry.getValue()) {
        probeIndices.add(probeMetadata.addProbe(definition));
      }
      toInstrument.add(new ToInstrumentInfo(entry.getValue().get(0), probeIndices));
    }
  }

  // Log & Span Decoration probes share the same instrumentor so only one definition should be
  // used to generate the instrumentation. This method generate a synthetic definition that
  // match the type of the probe to instrument: if at least one probe is LogProbe then we are
  // creating a LogProbe definition. The synthetic definition contains the union of all the capture,
  // snapshot and evaluateAt parameters.
  private static ProbeDefinition selectReferenceDefinition(
      List<ProbeDefinition> capturedContextProbes, ClassFileLines classFileLines) {
    boolean hasLogProbe = false;
    boolean hasOnlyExceptionProbe =
        capturedContextProbes.stream().allMatch(def -> def instanceof ExceptionProbe);
    MethodLocation evaluateAt = MethodLocation.EXIT;
    LogProbe.Capture capture = null;
    boolean captureSnapshot = false;
    ProbeCondition probeCondition = null;
    List<LogProbe.CaptureExpression> captureExpressions = null;
    Where where = capturedContextProbes.get(0).getWhere();
    ProbeId probeId = capturedContextProbes.get(0).getProbeId();
    for (ProbeDefinition definition : capturedContextProbes) {
      if (definition instanceof LogProbe) {
        if (definition instanceof ForceMethodInstrumentation) {
          where = Where.convertLineToMethod(definition.getWhere(), classFileLines);
        }
        hasLogProbe = true;
        LogProbe logProbe = (LogProbe) definition;
        captureSnapshot = captureSnapshot | logProbe.isCaptureSnapshot();
        capture = mergeCapture(capture, logProbe.getCapture());
        // captureExpressions = mergeCaptureExpressions(captureExpressions,
        // logProbe.getCaptureExpressions());
        if (probeCondition == null) {
          probeCondition = logProbe.getProbeCondition();
        }
      }
      if (definition.getEvaluateAt() == MethodLocation.ENTRY
          || definition.getEvaluateAt() == MethodLocation.DEFAULT) {
        evaluateAt = definition.getEvaluateAt();
      }
    }
    if (hasOnlyExceptionProbe) {
      // only exception probes return the first one
      return capturedContextProbes.get(0);
    }
    if (hasLogProbe) {
      return LogProbe.builder()
          .probeId(probeId)
          .when(probeCondition)
          .where(where)
          .evaluateAt(evaluateAt)
          .capture(capture)
          .captureSnapshot(captureSnapshot)
          .build();
    }
    return SpanDecorationProbe.builder()
        .probeId(probeId)
        .where(where)
        .evaluateAt(evaluateAt)
        .build();
  }

  private static LogProbe.Capture mergeCapture(
      LogProbe.Capture current, LogProbe.Capture newCapture) {
    if (current == null) {
      return newCapture;
    }
    if (newCapture == null) {
      return current;
    }
    return new LogProbe.Capture(
        Math.max(current.getMaxReferenceDepth(), newCapture.getMaxReferenceDepth()),
        Math.max(current.getMaxCollectionSize(), newCapture.getMaxCollectionSize()),
        Math.max(current.getMaxLength(), newCapture.getMaxLength()),
        Math.max(current.getMaxFieldCount(), newCapture.getMaxFieldCount()));
  }

  private static List<LogProbe.CaptureExpression> mergeCaptureExpressions(
      List<LogProbe.CaptureExpression> captureExpressions,
      List<LogProbe.CaptureExpression> newCaptureExpressions) {
    if (captureExpressions == null) {
      return newCaptureExpressions;
    }
    if (newCaptureExpressions == null) {
      return captureExpressions;
    }
    captureExpressions.addAll(newCaptureExpressions);
    return captureExpressions;
  }

  private InstrumentationResult.Status preCheckInstrumentation(
      Map<ProbeId, List<DiagnosticMessage>> diagnostics, MethodInfo methodInfo) {
    if ((methodInfo.getMethodNode().access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
      if (!instrumentTheWorld) {
        addDiagnosticForAllProbes(
            new DiagnosticMessage(
                DiagnosticMessage.Kind.ERROR, "Cannot instrument an abstract or native method"),
            diagnostics);
      }
      return InstrumentationResult.Status.ERROR;
    }
    ClassLoader classLoader = methodInfo.getClassLoader();
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

  private List<MethodNode> matchMethodDescription(
      ClassNode classNode, Where where, ClassFileLines classFileLines) {
    List<MethodNode> result = new ArrayList<>();
    try {
      for (MethodNode methodNode : classNode.methods) {
        if (where.isMethodMatching(methodNode, classFileLines) == Where.MethodMatching.MATCH) {
          result.add(methodNode);
        }
      }
    } catch (Exception ex) {
      LOGGER.warn("Cannot match method: {}", ex.toString());
    }
    return result;
  }

  private MethodNode matchSourceFile(
      ClassNode classNode, Where where, ClassFileLines classFileLines) {
    Where.SourceLine[] lines = where.getSourceLines();
    if (lines == null || lines.length == 0) {
      return null;
    }
    Where.SourceLine sourceLine = lines[0]; // assume only 1 range
    int matchingLine = sourceLine.getFrom();
    List<MethodNode> matchingMethods = classFileLines.getMethodsByLine(matchingLine);
    if (matchingMethods != null) {
      matchingMethods.forEach(
          methodNode -> {
            LOGGER.debug("Found lineNode {} method: {}", matchingLine, methodNode.name);
          });
      // pick the first matching method.
      // TODO need a way to disambiguate if multiple methods match the same line
      return matchingMethods.isEmpty() ? null : matchingMethods.get(0);
    }
    LOGGER.debug("Cannot find line: {} in class {}", matchingLine, classNode.name);
    return null;
  }

  private void dumpInstrumentedClassFile(String className, byte[] data) {
    if (config.isDynamicInstrumentationClassFileDumpEnabled()) {
      LOGGER.debug("Generated bytecode len: {}", data.length);
      Path classFilePath = dumpClassFile(className, data);
      if (classFilePath != null) {
        LOGGER.debug("Instrumented class saved as: {}", classFilePath);
      }
    }
  }

  private void dumpOriginalClassFile(String className, byte[] classfileBuffer) {
    if (config.isDynamicInstrumentationClassFileDumpEnabled()) {
      Path classFilePath = dumpClassFile(className + "_orig", classfileBuffer);
      if (classFilePath != null) {
        LOGGER.debug("Original class saved as: {}", classFilePath);
      }
    }
  }

  private static Path dumpClassFile(String className, byte[] classfileBuffer) {
    try {
      Path classFilePath = DUMP_PATH.resolve(className + ".class");
      Files.createDirectories(classFilePath.getParent());
      Files.write(classFilePath, classfileBuffer, StandardOpenOption.CREATE);
      return classFilePath;
    } catch (IOException e) {
      LOGGER.error("", e);
      return null;
    }
  }

  /**
   * A {@link org.objectweb.asm.ClassVisitor} that uses {@link
   * org.objectweb.asm.commons.JSRInlinerAdapter} to remove JSR instructions and inlines the
   * referenced subroutines. This allows pre-Java 6 classes with finally blocks to be successfully
   * transformed. Without this an IllegalArgumentException for "JSR/RET are not supported with
   * computeFrames option" would be thrown when writing the transformed class.
   */
  static class JsrInliningClassVisitor extends ClassVisitor {
    protected JsrInliningClassVisitor(ClassVisitor parent) {
      super(Opcodes.ASM9, parent);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      return new JSRInlinerAdapter(mv, access, name, descriptor, signature, exceptions);
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
        LOGGER.debug("getCommonSuperClass failed: ", ex);
        return tpDatadogClassLoader.describe("java.lang.Object").resolve().getInternalName();
      }
    }
  }
}
