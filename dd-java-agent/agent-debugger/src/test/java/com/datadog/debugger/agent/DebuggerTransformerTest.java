package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.CorrelationAccess;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.FieldExtractor;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.ValueConverter;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;
import utils.SourceCompiler;

public class DebuggerTransformerTest {
  private static final String LANGUAGE = "java";
  private static final String PROBE_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";
  private static final String SERVICE_NAME = "service-name";
  private static final long ORG_ID = 2;
  private static final boolean FAST_TESTS = Boolean.getBoolean("fast-tests");

  enum InstrumentationKind {
    ENTRY_EXIT,
    LINE,
    LINE_RANGE
  }

  enum ExceptionKind {
    NONE,
    UNHANDLED,
    HANDLED
  }

  static class TestSnapshotListener implements DebuggerContext.Sink {
    boolean skipped;
    List<Snapshot> snapshots = new ArrayList<>();
    Map<String, List<DiagnosticMessage>> errors = new HashMap<>();

    @Override
    public void skipSnapshot(String probeId, DebuggerContext.SkipCause cause) {
      skipped = true;
    }

    @Override
    public void addSnapshot(Snapshot snapshot) {
      this.snapshots.add(snapshot);
    }

    @Override
    public void addDiagnostics(String probeId, List<DiagnosticMessage> messages) {
      errors.computeIfAbsent(probeId, k -> new ArrayList<>()).addAll(messages);
    }
  }

  private static class TrackingClassFileTransformer implements ClassFileTransformer {
    private final DebuggerTransformer delegate;
    private final String targetClassName;
    private final byte[][] codeOutput;

    TrackingClassFileTransformer(String targetClassName, SnapshotProbe probe, byte[][] codeOutput) {
      assertTrue(codeOutput != null && codeOutput.length == 2);
      this.targetClassName = targetClassName;
      this.delegate =
          new DebuggerTransformer(
              Config.get(),
              new Configuration(SERVICE_NAME, ORG_ID, Collections.singletonList(probe)),
              null);
      this.codeOutput = codeOutput;
    }

    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer)
        throws IllegalClassFormatException {
      byte[] transformed =
          delegate.transform(
              loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
      if (className.equals(targetClassName)) {
        codeOutput[0] = classfileBuffer;
        codeOutput[1] = transformed;
      } else {
        assertNull(transformed);
      }
      return transformed;
    }
  }

  static final String LINE_PROBE_MARKER = "#line#";
  static final String LINE_RANGE_START_MARKER = "#start#";
  static final String LINE_RANGE_END_MARKER = "#end#";
  static final String VAR_NAME = "var";
  static final String SCOPED_VAR_NAME = "scoped";
  static final String SCOPED_VAR_TYPE = "int";
  static final Object SCOPED_VAR_VALUE = 10;

  private static Instrumentation instr;
  private static Template classTemplate;

  private static Tracer mockTracer;
  private static Tracer noopTracer;

  private static final Snapshot.CapturedValue[] CORRELATION_FIELDS = new Snapshot.CapturedValue[2];

  @BeforeAll
  static void setupAll() throws Exception {
    // disable tracer integration
    System.setProperty("dd." + TraceInstrumentationConfig.TRACE_ENABLED, "false");

    Field fld = CorrelationAccess.class.getDeclaredField("REUSE_INSTANCE");
    fld.setAccessible(true);
    fld.set(null, false);

    // setup the tracer
    noopTracer = GlobalTracer.get();
    Tracer mockTracer = mock(Tracer.class);
    when(mockTracer.getTraceId()).thenReturn("1");
    when(mockTracer.getSpanId()).thenReturn("2");
    GlobalTracer.forceRegister(mockTracer);

    // prepare the correlation fields golden muster
    CORRELATION_FIELDS[0] =
        Snapshot.CapturedValue.of("dd.trace_id", "java.lang.String", mockTracer.getTraceId());
    CORRELATION_FIELDS[1] =
        Snapshot.CapturedValue.of("dd.span_id", "java.lang.String", mockTracer.getSpanId());

    instr = ByteBuddyAgent.install();
    freemarker.template.Configuration cfg =
        new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_29);
    cfg.setBooleanFormat("c");
    classTemplate =
        new Template(
            "classTemplate",
            new InputStreamReader(
                DebuggerTransformerTest.class.getResourceAsStream("/TargetClass.ftlh")),
            cfg);
  }

  @AfterEach
  void tearDown() {
    // disable tracer integration
    System.setProperty("dd." + TraceInstrumentationConfig.TRACE_ENABLED, "false");
    ProbeRateLimiter.resetGlobalRate();
  }

  @BeforeEach
  void setup() {
    DebuggerContext.init(null, null, null);
  }

  private byte[] getClassFileBytes(Class<?> clazz) {
    URL resource = clazz.getResource(clazz.getSimpleName() + ".class");
    byte[] buffer = new byte[4096];
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try (InputStream is = resource.openStream()) {
      int readBytes;
      while ((readBytes = is.read(buffer)) != -1) {
        os.write(buffer, 0, readBytes);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return os.toByteArray();
  }

  @Test
  public void testDump() {
    Config config = mock(Config.class);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    File instrumentedClassFile = new File("/tmp/debugger/java.util.ArrayList.class");
    File origClassFile = new File("/tmp/debugger/java.util.ArrayList_orig.class");
    if (instrumentedClassFile.exists()) {
      instrumentedClassFile.delete();
    }
    if (origClassFile.exists()) {
      origClassFile.delete();
    }
    SnapshotProbe snapshotProbe =
        SnapshotProbe.builder().where("java.util.ArrayList", "add").build();
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(
            config,
            new Configuration(SERVICE_NAME, ORG_ID, Collections.singletonList(snapshotProbe)),
            null);
    debuggerTransformer.transform(
        ClassLoader.getSystemClassLoader(),
        "java.util.ArrayList",
        ArrayList.class,
        null,
        getClassFileBytes(ArrayList.class));
    Assert.assertTrue(instrumentedClassFile.exists());
    Assert.assertTrue(origClassFile.exists());
    Assert.assertTrue(instrumentedClassFile.delete());
    Assert.assertTrue(origClassFile.delete());
  }

  @Test
  public void testMultiProbes() {
    doTestMultiProbes(
        Class::getName,
        new ProbeTestInfo(ArrayList.class, "add"),
        new ProbeTestInfo(HashMap.class, "<init>", "void ()"));
  }

  @Test
  public void testMultiProbesSimpleName() {
    doTestMultiProbes(
        Class::getSimpleName,
        new ProbeTestInfo(ArrayList.class, "add"),
        new ProbeTestInfo(HashMap.class, "<init>", "void ()"));
  }

  private void doTestMultiProbes(
      Function<Class<?>, String> getClassName, ProbeTestInfo... probeInfos) {
    Config config = mock(Config.class);
    List<SnapshotProbe> snapshotProbes = new ArrayList<>();
    for (ProbeTestInfo probeInfo : probeInfos) {
      String className = getClassName.apply(probeInfo.clazz);
      SnapshotProbe snapshotProbe =
          SnapshotProbe.builder()
              .where(className, probeInfo.methodName, probeInfo.signature)
              .build();
      snapshotProbes.add(snapshotProbe);
    }
    Configuration configuration = new Configuration(SERVICE_NAME, ORG_ID, snapshotProbes);
    DebuggerTransformer debuggerTransformer = new DebuggerTransformer(config, configuration);
    for (ProbeTestInfo probeInfo : probeInfos) {
      byte[] newClassBuffer =
          debuggerTransformer.transform(
              ClassLoader.getSystemClassLoader(),
              probeInfo.clazz.getName(), // always FQN
              probeInfo.clazz,
              null,
              getClassFileBytes(probeInfo.clazz));
      Assert.assertNotNull(newClassBuffer);
    }
    byte[] newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "java.util.HashSet",
            HashSet.class,
            null,
            getClassFileBytes(HashSet.class));
    Assert.assertNull(newClassBuffer);
  }

  static class ProbeTestInfo {
    final Class<?> clazz;
    final String methodName;
    final String signature;

    public ProbeTestInfo(Class<?> clazz, String methodName) {
      this(clazz, methodName, null);
    }

    public ProbeTestInfo(Class<?> clazz, String methodName, String signature) {
      this.clazz = clazz;
      this.methodName = methodName;
      this.signature = signature;
    }
  }

  @Test
  public void testDeactivatedProbes() {
    Config config = mock(Config.class);
    List<SnapshotProbe> snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID)
                .active(false)
                .where("java.lang.String", "toString")
                .build(),
            SnapshotProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID)
                .active(false)
                .where("java.util.HashMap", "<init>", "void ()")
                .build());
    Configuration configuration = new Configuration(SERVICE_NAME, ORG_ID, snapshotProbes);
    DebuggerTransformer debuggerTransformer = new DebuggerTransformer(config, configuration);
    byte[] newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "java.lang.String",
            String.class,
            null,
            getClassFileBytes(String.class));
    Assert.assertNull(newClassBuffer);
    newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "java.util.HashMap",
            HashMap.class,
            null,
            getClassFileBytes(HashMap.class));
    Assert.assertNull(newClassBuffer);
  }

  @Test
  public void testBlockedProbes() {
    Config config = mock(Config.class);
    List<SnapshotProbe> snapshotProbes =
        Arrays.asList(
            SnapshotProbe.builder()
                .language(LANGUAGE)
                .probeId(PROBE_ID)
                .active(true)
                .where("java.lang.String", "toString")
                .build());
    Configuration configuration = new Configuration(SERVICE_NAME, ORG_ID, snapshotProbes);
    AtomicReference<InstrumentationResult> lastResult = new AtomicReference<>(null);
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(
            config, configuration, ((definition, result) -> lastResult.set(result)));
    byte[] newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "java.lang.String",
            String.class,
            null,
            getClassFileBytes(String.class));
    Assert.assertNull(newClassBuffer);
    Assert.assertNotNull(lastResult.get());
    Assert.assertTrue(lastResult.get().isBlocked());
    Assert.assertFalse(lastResult.get().isInstalled());
    Assert.assertEquals("java.lang.String", lastResult.get().getTypeName());
  }

  @Test
  public void classBeingRedefinedNull() {
    Config config = mock(Config.class);
    SnapshotProbe snapshotProbe = SnapshotProbe.builder().where("ArrayList", "add").build();
    Configuration configuration =
        new Configuration(SERVICE_NAME, ORG_ID, Collections.singletonList(snapshotProbe));
    AtomicReference<InstrumentationResult> lastResult = new AtomicReference<>(null);
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(
            config, configuration, ((definition, result) -> lastResult.set(result)));
    byte[] newClassBuffer =
        debuggerTransformer.transform(
            ClassLoader.getSystemClassLoader(),
            "java.util.ArrayList",
            null, // classBeingRedefined
            null,
            getClassFileBytes(ArrayList.class));
    Assert.assertNotNull(newClassBuffer);
    Assert.assertNotNull(lastResult.get());
    Assert.assertFalse(lastResult.get().isBlocked());
    Assert.assertTrue(lastResult.get().isInstalled());
    Assert.assertEquals("java.util.ArrayList", lastResult.get().getTypeName());
  }

  @ParameterizedTest(
      name =
          "{index} ==> arguments: {0} = {1}, return: {2} = {3}, static: {4}, debug_info: {5}, kind: {6}, correlated: {7}")
  @MethodSource("transformationsSource")
  public void testTransformations(
      Class<?> argumentType,
      Object[] argValues,
      Class<?> returnType,
      Object[] retValues,
      boolean isStatic,
      SourceCompiler.DebugInfo debugInfo,
      InstrumentationKind kind,
      boolean isCorrelated)
      throws Exception {
    if (FAST_TESTS) {
      // in dev, skip those tests which take a long time to execute
      return;
    }
    System.setProperty(
        "dd." + TraceInstrumentationConfig.TRACE_ENABLED, Boolean.toString(isCorrelated));

    System.out.println("=== " + debugInfo + ", " + kind);
    String targetClassName = "AClass" + System.nanoTime();
    String targetMethodName = "testMethod";
    Object argumentInputValue = argValues[0];
    Object argumentOutputValue = argValues[1];
    Object returnValue = retValues[0];

    String classSource =
        prepareClassSource(
            targetClassName,
            targetMethodName,
            argumentType,
            argumentOutputValue,
            returnType,
            returnValue,
            isStatic);

    SnapshotProbe probe = prepareProbe(classSource, targetClassName, targetMethodName, kind);

    TestSnapshotListener listener = new TestSnapshotListener();
    // add the listener to Snapshot class via reflection
    DebuggerContext.init(
        listener,
        (id, clazz) -> {
          String typeName = probe.getWhere().getTypeName();
          String methodName = probe.getWhere().getMethodName();
          String sourceFile = probe.getWhere().getSourceFile();
          Where.SourceLine[] sourceLines = probe.getWhere().getSourceLines();
          List<String> lines =
              sourceLines != null
                  ? Arrays.stream(sourceLines)
                      .map(Where.SourceLine::toString)
                      .collect(Collectors.toList())
                  : null;
          return new Snapshot.ProbeDetails(
              id, new Snapshot.ProbeLocation(typeName, methodName, sourceFile, lines));
        },
        null);

    // attach the debugger transformer, load the test target class and run the test method
    byte[][] codeOutput = new byte[2][];
    ClassFileTransformer t = new TrackingClassFileTransformer(targetClassName, probe, codeOutput);
    try {
      byte[] classData =
          SourceCompiler.compile(targetClassName, classSource, debugInfo).get(targetClassName);

      instr.addTransformer(t);

      Class<?> targetClass =
          Reflect.on(DebuggerTransformerTest.class.getClassLoader())
              .call("defineClass", targetClassName, classData, 0, classData.length)
              .get();

      for (ExceptionKind exceptionKind : EnumSet.allOf(ExceptionKind.class)) {
        try {
          if (isStatic) {
            Reflect.onClass(targetClass)
                .call(targetMethodName, argumentInputValue, exceptionKind.ordinal());
          } else {
            Object instance = targetClass.getConstructor().newInstance();
            Reflect.on(instance)
                .call(targetMethodName, argumentInputValue, exceptionKind.ordinal());
          }
        } catch (Throwable ignored) {
        }
      }
    } finally {
      instr.removeTransformer(t);
    }
    assertTransformation(codeOutput[0], codeOutput[1]);

    assertFalse(listener.skipped);
    if (isMethodProbe(probe)) {
      assertEquals(EnumSet.allOf(ExceptionKind.class).size(), listener.snapshots.size());
    }

    for (Snapshot snapshot : listener.snapshots) {
      List<CapturedStackFrame> stackTrace = snapshot.getStack();
      assertNotNull(stackTrace);
      assertFalse(stackTrace.get(0).getFunction().contains(Snapshot.class.getName()));

      assertEquals(probe.getId(), snapshot.getProbe().getId());
    }

    int expectedLineFrom =
        kind != InstrumentationKind.ENTRY_EXIT
            ? probe.getWhere().getSourceLines()[0].getFrom()
            : -1;
    int expectedLineTill =
        kind != InstrumentationKind.ENTRY_EXIT
            ? probe.getWhere().getSourceLines()[0].getTill()
            : -1;
    CaptureAssertionHelper helper =
        new CaptureAssertionHelper(
            kind,
            debugInfo,
            argumentType.getName(),
            argumentInputValue,
            argumentOutputValue,
            returnType.getName(),
            returnValue,
            expectedLineFrom,
            expectedLineTill,
            isCorrelated ? CORRELATION_FIELDS : null,
            listener.snapshots);

    if (isMethodProbe(probe)) {
      for (ExceptionKind exceptionKind : EnumSet.allOf(ExceptionKind.class)) {
        System.out.println("====== " + exceptionKind);
        helper.assertCaptures(exceptionKind);
      }
    }
  }

  private static boolean isMethodProbe(SnapshotProbe probe) {
    Where.SourceLine[] sourceLines = probe.getWhere().getSourceLines();
    return sourceLines == null || sourceLines.length <= 0;
  }

  /*
   * Build a DebuggerProbe definition based on the provided arguments
   */
  private SnapshotProbe prepareProbe(
      String sourceCode, String targetClassName, String targetMethodName, InstrumentationKind kind)
      throws Exception {
    SnapshotProbe.Builder builder = SnapshotProbe.builder().probeId(UUID.randomUUID().toString());
    // add depth 1 field destructuring
    builder.capture(
        ValueConverter.DEFAULT_REFERENCE_DEPTH,
        ValueConverter.DEFAULT_COLLECTION_SIZE,
        ValueConverter.DEFAULT_LENGTH,
        1,
        FieldExtractor.DEFAULT_FIELD_COUNT);
    if (kind == InstrumentationKind.LINE) {
      // locate the specially marked line in the source code
      int line = findLine(sourceCode, LINE_PROBE_MARKER);
      if (line == -1) {
        throw new RuntimeException("Unable to find '" + LINE_PROBE_MARKER + "' marker");
      }
      return builder.where(targetClassName, targetMethodName, null, line, null).build();
    } else if (kind == InstrumentationKind.LINE_RANGE) {
      // locate the specially marked lines in the source code
      int from = findLine(sourceCode, LINE_RANGE_START_MARKER);
      if (from == -1) {
        throw new RuntimeException("Unable to find '" + LINE_RANGE_START_MARKER + "' marker");
      }
      int till = findLine(sourceCode, LINE_RANGE_END_MARKER);
      if (till == -1) {
        throw new RuntimeException("Unable to find '" + LINE_RANGE_END_MARKER + "' marker");
      }
      return builder.where(targetClassName, targetMethodName, null, from, till, null).build();
    }
    return builder.where(targetClassName, targetMethodName).build();
  }

  private int findLine(String data, String text) throws Exception {
    try (BufferedReader br = new BufferedReader(new StringReader(data))) {
      int lineNo = 1;
      String line = null;
      while ((line = br.readLine()) != null) {
        if (line.contains(text)) {
          return lineNo;
        }
        lineNo++;
      }
      return -1;
    }
  }

  /*
   * Generate source code representation based on the freemarker template and the given parameters
   */
  private String prepareClassSource(
      String targetClassName,
      String targetMethodName,
      Class<?> argumentType,
      Object argumentOutputValue,
      Class<?> returnType,
      Object returnValue,
      boolean isStatic)
      throws IOException, TemplateException {
    Map<String, Object> map = new HashMap<>();
    map.put("varName", VAR_NAME);
    map.put("scopedVarName", SCOPED_VAR_NAME);
    map.put("scopedVarType", SCOPED_VAR_TYPE);
    map.put("scopedVarValue", formatValue(SCOPED_VAR_VALUE));
    map.put("className", targetClassName);
    map.put("methodModifiers", isStatic ? "static" : "");
    map.put("returnType", returnType.getName());
    map.put("returnValue", formatValue(returnValue));
    map.put("methodName", targetMethodName);
    map.put("argumentType", argumentType.getName());
    map.put("argumentValue", formatValue(argumentOutputValue));
    map.put("lineProbe", LINE_PROBE_MARKER);
    map.put("lineRangeStart", LINE_RANGE_START_MARKER);
    map.put("lineRangeEnd", LINE_RANGE_END_MARKER);

    StringWriter sw = new StringWriter();
    classTemplate.process(map, sw);

    return sw.toString();
  }

  private Object formatValue(Object value) {
    if (value instanceof String) {
      return "\"" + value + "\"";
    }
    if (value instanceof Character) {
      return "'" + value + "'";
    }
    return value;
  }

  // parameterized test source
  private static Stream<Arguments> transformationsSource() {
    List<Arguments> arguments = new ArrayList<>();
    Map<Class<?>, Object[]> typeValueMap =
        new HashMap<Class<?>, Object[]>() {
          {
            put(byte.class, new Object[] {(byte) 1, (byte) 0});
            put(char.class, new Object[] {'a', 'z'});
            put(short.class, new Object[] {(short) 3, (short) 0});
            put(int.class, new Object[] {(int) 4, (int) 0});
            put(long.class, new Object[] {(long) 5, (long) 0});
            put(float.class, new Object[] {(float) 6, (float) 0});
            put(double.class, new Object[] {(double) 8, (double) 0});
            put(boolean.class, new Object[] {true, false});
            put(String.class, new Object[] {"9", ""});
          }
        };

    for (Map.Entry<Class<?>, Object[]> argEntry : typeValueMap.entrySet()) {
      for (Map.Entry<Class<?>, Object[]> returnEntry : typeValueMap.entrySet()) {
        for (InstrumentationKind kind : EnumSet.allOf(InstrumentationKind.class)) {
          for (SourceCompiler.DebugInfo debugInfo :
              EnumSet.of(SourceCompiler.DebugInfo.ALL, SourceCompiler.DebugInfo.NONE)) {
            for (boolean isStatic : new boolean[] {true, false}) {
              for (boolean isCorrelated : new boolean[] {true, false}) {
                arguments.add(
                    Arguments.of(
                        argEntry.getKey(),
                        argEntry.getValue(),
                        returnEntry.getKey(),
                        returnEntry.getValue(),
                        isStatic,
                        debugInfo,
                        kind,
                        isCorrelated));
              }
            }
          }
        }
      }
    }
    return arguments.stream();
  }

  private static void assertTransformation(byte[] original, byte[] transformed) {
    Assert.assertNotNull(transformed);
    String diff = diff(asmify(transformed), asmify(original));
    // make sure the instrumentation actually changed anything
    assertFalse(diff.isEmpty());
    // verify the instrumented code by the ASM provided verifier
    assertTrue(verify(transformed), verifyPrint(transformed));
  }

  private static String asmify(byte[] code) {
    StringWriter sw = new StringWriter();
    TraceClassVisitor v = new TraceClassVisitor(new PrintWriter(sw));
    new ClassReader(code).accept(v, 0);
    return sw.toString();
  }

  private static String diff(String modified, String orig) {
    StringBuilder sb = new StringBuilder();

    String[] modArr = modified.split("\\n");
    String[] orgArr = orig.split("\\n");

    // number of lines of each file
    int modLen = modArr.length;
    int origLen = orgArr.length;

    // opt[i][j] = length of LCS of x[i..M] and y[j..N]
    int[][] opt = new int[modLen + 1][origLen + 1];

    // compute length of LCS and all subproblems via dynamic programming
    for (int i = modLen - 1; i >= 0; i--) {
      for (int j = origLen - 1; j >= 0; j--) {
        if (modArr[i].equals(orgArr[j])) {
          opt[i][j] = opt[i + 1][j + 1] + 1;
        } else {
          opt[i][j] = Math.max(opt[i + 1][j], opt[i][j + 1]);
        }
      }
    }

    // recover LCS itself and print out non-matching lines to standard output
    int modIndex = 0;
    int origIndex = 0;
    while (modIndex < modLen && origIndex < origLen) {
      if (modArr[modIndex].equals(orgArr[origIndex])) {
        modIndex++;
        origIndex++;
      } else if (opt[modIndex + 1][origIndex] >= opt[modIndex][origIndex + 1]) {
        sb.append(modArr[modIndex++].trim()).append('\n');
      } else {
        origIndex++;
      }
    }

    // dump out one remainder of one string if the other is exhausted
    while (modIndex < modLen || origIndex < origLen) {
      if (modIndex == modLen) {
        origIndex++;
      } else if (origIndex == origLen) {
        sb.append(orgArr[modIndex++].trim()).append('\n');
      }
    }
    return sb.toString().trim();
  }

  private static boolean verify(byte[] code) {
    StringWriter sw = new StringWriter();

    ClassReader cr = new ClassReader(code);
    CheckClassAdapter.verify(cr, false, new PrintWriter(sw));

    return !sw.toString().contains("Error at ");
  }

  private static String verifyPrint(byte[] code) {
    StringWriter sw = new StringWriter();

    ClassReader cr = new ClassReader(code);
    CheckClassAdapter.verify(cr, true, new PrintWriter(sw));
    return sw.toString();
  }
}
