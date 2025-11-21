package com.datadog.debugger.agent;

import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;
import static com.datadog.debugger.util.MoshiSnapshotTestHelper.VALUE_ADAPTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static utils.TestHelper.setFieldInConfig;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.datadog.debugger.util.SerializerWithLimits;
import com.datadog.debugger.util.TestSnapshotListener;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.util.Redaction;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

public class CapturingTestBase {
  protected static final String LANGUAGE = "java";

  protected static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f5", 0);

  protected static final String SERVICE_NAME = "service-name";

  private static final JsonAdapter<Map<String, Object>> GENERIC_ADAPTER =
      MoshiHelper.createGenericAdapter();

  protected Config config;
  protected ConfigurationUpdater configurationUpdater;
  protected ClassFileTransformer currentTransformer;
  protected Instrumentation instr;
  protected MockInstrumentationListener instrumentationListener;
  protected ProbeStatusSink probeStatusSink;
  protected ProbeMetadata probeMetadata = new ProbeMetadata();

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
    ProbeRateLimiter.resetAll();
    Assertions.assertFalse(DebuggerContext.isInProbe());
    Redaction.clearUserDefinedTypes();
  }

  @BeforeEach
  public void before() {
    setFieldInConfig(Config.get(), "dynamicInstrumentationCaptureTimeout", 200);
    instr = ByteBuddyAgent.install();
  }

  protected void assertCaptureArgs(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue capturedValue = context.getArguments().get(name);
    assertEquals(typeName, capturedValue.getType());
    assertEquals(value, MoshiSnapshotTestHelper.getValue(capturedValue));
  }

  protected void assertCaptureFieldCount(CapturedContext context, int expectedFieldCount) {
    assertEquals(expectedFieldCount, getFields(context.getArguments().get("this")).size());
  }

  public static Map<String, CapturedContext.CapturedValue> getFields(
      CapturedContext.CapturedValue capturedValue) {
    try {
      CapturedContext.CapturedValue valued = VALUE_ADAPTER.fromJson(capturedValue.getStrValue());
      Map<String, CapturedContext.CapturedValue> results = new HashMap<>();
      if (valued.getNotCapturedReason() != null) {
        results.put(
            "@" + NOT_CAPTURED_REASON,
            CapturedContext.CapturedValue.notCapturedReason(
                null, null, valued.getNotCapturedReason()));
      }
      if (valued.getValue() == null) {
        return results;
      }
      results.putAll((Map<String, CapturedContext.CapturedValue>) valued.getValue());
      return results;
    } catch (IOException e) {
      e.printStackTrace();
      return Collections.emptyMap();
    }
  }

  protected void assertCaptureFields(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue field = getFields(context.getArguments().get("this")).get(name);
    assertEquals(typeName, field.getType());
    assertEquals(value, MoshiSnapshotTestHelper.getValue(field));
  }

  protected void assertCaptureFields(
      CapturedContext context, String name, String typeName, Collection<?> collection) {
    CapturedContext.CapturedValue field = getFields(context.getArguments().get("this")).get(name);
    assertEquals(typeName, field.getType());
    Iterator<?> iterator = collection.iterator();
    for (Object obj : getCollection(field)) {
      if (iterator.hasNext()) {
        assertEquals(iterator.next(), obj);
      } else {
        Assertions.fail("not same number of elements");
      }
    }
  }

  private static Collection<?> getCollection(CapturedContext.CapturedValue capturedValue) {
    try {
      if (capturedValue.getStrValue() == null) {
        return (Collection<?>) capturedValue.getValue();
      }
      Map<String, Object> capturedValueMap = GENERIC_ADAPTER.fromJson(capturedValue.getStrValue());
      List<Object> elements = (List<Object>) capturedValueMap.get("elements");
      if (elements == null) {
        Assertions.fail("not a collection");
      }
      List<Object> result = new ArrayList<>();
      for (Object obj : elements) {
        Map<String, Object> element = (Map<String, Object>) obj;
        String type = (String) element.get("type");
        if (type == null) {
          Assertions.fail("no type for element");
        }
        if (SerializerWithLimits.isPrimitive(type)) {
          result.add(element.get("value"));
        } else {
          Assertions.fail("not implemented");
        }
      }
      return result;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  protected void assertCaptureFields(
      CapturedContext context, String name, String typeName, Map<Object, Object> expectedMap) {
    CapturedContext.CapturedValue field = getFields(context.getArguments().get("this")).get(name);
    assertEquals(typeName, field.getType());
    Map<Object, Object> map = getMap(field);
    assertEquals(expectedMap.size(), map.size());
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      assertTrue(expectedMap.containsKey(entry.getKey()));
      assertEquals(expectedMap.get(entry.getKey()), entry.getValue());
    }
  }

  private Map<Object, Object> getMap(CapturedContext.CapturedValue capturedValue) {
    try {
      if (capturedValue.getStrValue() == null) {
        return (Map<Object, Object>) capturedValue.getValue();
      }
      Map<String, Object> capturedValueMap = GENERIC_ADAPTER.fromJson(capturedValue.getStrValue());
      List<Object> entries = (List<Object>) capturedValueMap.get("entries");
      if (entries == null) {
        Assertions.fail("not a map");
      }
      Map<Object, Object> result = new HashMap<>();
      for (Object obj : entries) {
        List<Object> entry = (List<Object>) obj;
        Map<String, Object> key = (Map<String, Object>) entry.get(0);
        Map<String, Object> value = (Map<String, Object>) entry.get(1);
        result.put(key.get("value"), value.get("value"));
      }
      return result;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  protected void assertCaptureFieldsNotCaptured(
      CapturedContext context, String name, String expectedReasonRegEx) {
    CapturedContext.CapturedValue field = getFields(context.getArguments().get("this")).get(name);
    assertTrue(
        field.getNotCapturedReason().matches(expectedReasonRegEx), field.getNotCapturedReason());
  }

  protected void assertCaptureLocals(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue localVar = context.getLocals().get(name);
    assertEquals(typeName, localVar.getType());
    assertEquals(value, MoshiSnapshotTestHelper.getValue(localVar));
  }

  protected void assertCaptureLocals(
      CapturedContext context, String name, String typeName, Map<String, String> expectedFields) {
    CapturedContext.CapturedValue localVar = context.getLocals().get(name);
    assertEquals(typeName, localVar.getType());
    Map<String, CapturedContext.CapturedValue> fields = getFields(localVar);
    for (Map.Entry<String, String> entry : expectedFields.entrySet()) {
      assertTrue(fields.containsKey(entry.getKey()));
      CapturedContext.CapturedValue fieldCapturedValue = fields.get(entry.getKey());
      if (fieldCapturedValue.getNotCapturedReason() != null) {
        assertEquals(entry.getValue(), String.valueOf(fieldCapturedValue.getNotCapturedReason()));
      } else {
        assertEquals(entry.getValue(), String.valueOf(fieldCapturedValue.getValue()));
      }
    }
  }

  protected void assertCaptureReturnValue(CapturedContext context, String typeName, String value) {
    CapturedContext.CapturedValue returnValue = context.getLocals().get("@return");
    assertEquals(typeName, returnValue.getType());
    assertEquals(value, MoshiSnapshotTestHelper.getValue(returnValue));
  }

  protected void assertCaptureReturnValue(
      CapturedContext context, String typeName, Map<String, String> expectedFields) {
    CapturedContext.CapturedValue returnValue = context.getLocals().get("@return");
    assertEquals(typeName, returnValue.getType());
    Map<String, CapturedContext.CapturedValue> fields = getFields(returnValue);
    for (Map.Entry<String, String> entry : expectedFields.entrySet()) {
      assertTrue(fields.containsKey(entry.getKey()));
      CapturedContext.CapturedValue fieldCapturedValue = fields.get(entry.getKey());
      if (fieldCapturedValue.getNotCapturedReason() != null) {
        assertEquals(entry.getValue(), String.valueOf(fieldCapturedValue.getNotCapturedReason()));
      } else {
        assertEquals(entry.getValue(), String.valueOf(fieldCapturedValue.getValue()));
      }
    }
  }

  protected void assertCaptureStaticFieldsNotCaptured(
      CapturedContext context, String name, String expectedReasonRegEx) {
    CapturedContext.CapturedValue field = context.getStaticFields().get(name);
    assertTrue(
        field.getNotCapturedReason().matches(expectedReasonRegEx), field.getNotCapturedReason());
  }

  protected void assertCaptureThrowable(
      CapturedContext context, String typeName, String message, String methodName, int lineNumber) {
    CapturedContext.CapturedThrowable throwable = context.getCapturedThrowable();
    assertCaptureThrowable(throwable, typeName, message, methodName, lineNumber);
  }

  protected void assertCaptureThrowable(
      CapturedContext.CapturedThrowable throwable,
      String typeName,
      String message,
      String methodName,
      int lineNumber) {
    assertNotNull(throwable);
    assertEquals(typeName, throwable.getType());
    assertEquals(message, throwable.getMessage());
    assertNotNull(throwable.getStacktrace());
    Assertions.assertFalse(throwable.getStacktrace().isEmpty());
    assertEquals(methodName, throwable.getStacktrace().get(0).getFunction());
    assertEquals(lineNumber, throwable.getStacktrace().get(0).getLineNumber());
  }

  protected void assertCaptureExpressions(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue expression = context.getCaptureExpressions().get(name);
    assertEquals(typeName, expression.getType());
    assertEquals(value, MoshiSnapshotTestHelper.getValue(expression));
  }

  protected TestSnapshotListener installMethodProbe(
      String typeName, String methodName, String signature) {
    LogProbe logProbe =
        createMethodProbe(CapturedSnapshotTest.PROBE_ID, typeName, methodName, signature);
    return installProbes(logProbe);
  }

  protected TestSnapshotListener installLineProbe(ProbeId id, String typeName, int line) {
    LogProbe logProbe = createLineProbe(id, typeName, line);
    return installProbes(logProbe);
  }

  protected static LogProbe createMethodProbe(
      ProbeId id, String typeName, String methodName, String signature) {
    return createProbeBuilder(id, typeName, methodName, signature).build();
  }

  protected static LogProbe createLineProbe(ProbeId id, String sourceFile, int line) {
    return createProbeBuilder(id, sourceFile, line).build();
  }

  protected TestSnapshotListener installProbes(ProbeDefinition... probes) {
    return installProbes(
        Configuration.builder().setService(CapturedSnapshotTest.SERVICE_NAME).add(probes).build());
  }

  public static LogProbe.Builder createProbeBuilder(
      ProbeId id, String typeName, String methodName, String signature) {
    return createProbeBuilder(id)
        .captureSnapshot(true)
        .where(typeName, methodName, signature, (String[]) null)
        // Increase sampling limit to avoid being sampled during tests
        .sampling(new LogProbe.Sampling(100));
  }

  public static LogProbe.Builder createProbeBuilder(ProbeId id, String sourceFile, int line) {
    return createProbeBuilder(id)
        .captureSnapshot(true)
        .where(sourceFile, line)
        // Increase sampling limit to avoid being sampled during tests
        .sampling(new LogProbe.Sampling(100));
  }

  public static LogProbe.Builder createProbeBuilder(ProbeId id) {
    return LogProbe.builder().language(LANGUAGE).probeId(id);
  }

  protected TestSnapshotListener installProbes(Configuration configuration) {
    config = getConfig();
    instrumentationListener = new MockInstrumentationListener();
    probeStatusSink = mock(ProbeStatusSink.class);
    TestSnapshotListener listener = new TestSnapshotListener(config, probeStatusSink);
    configurationUpdater =
        new ConfigurationUpdater(
            instr,
            DebuggerTransformer::new,
            config,
            new DebuggerSink(config, probeStatusSink),
            new ClassesToRetransformFinder());
    currentTransformer =
        new DebuggerTransformer(
            config,
            configuration,
            instrumentationListener,
            configurationUpdater.getProbeMetadata(),
            listener);
    instr.addTransformer(currentTransformer);
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.initProbeResolver(configurationUpdater::resolve);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer());

    for (LogProbe probe : configuration.getLogProbes()) {
      if (probe.getSampling() != null) {
        ProbeRateLimiter.setRate(
            probe.getId(), probe.getSampling().getEventsPerSecond(), probe.isCaptureSnapshot());
      }
    }
    if (configuration.getSampling() != null) {
      ProbeRateLimiter.setGlobalSnapshotRate(configuration.getSampling().getEventsPerSecond());
    }
    return listener;
  }

  public static Config getConfig() {
    Config config = Config.get();
    setFieldInConfig(config, "dynamicInstrumentationEnabled", true);
    setFieldInConfig(config, "dynamicInstrumentationClassFileDumpEnabled", true);
    setFieldInConfig(config, "dynamicInstrumentationVerifyByteCode", false);
    setFieldInConfig(config, "debuggerCodeOriginMaxUserFrames", 20);
    setFieldInConfig(config, "dynamicInstrumentationSnapshotUrl", "http://localhost:8080");
    return config;
  }

  protected TestSnapshotListener installMethodProbeAtExit(
      String typeName, String methodName, String signature) {
    LogProbe logProbes =
        createMethodProbeAtExit(CapturedSnapshotTest.PROBE_ID, typeName, methodName, signature);
    return installProbes(logProbes);
  }

  protected static LogProbe createMethodProbeAtExit(
      ProbeId id, String typeName, String methodName, String signature) {
    return createProbeBuilder(id, typeName, methodName, signature)
        .evaluateAt(MethodLocation.EXIT)
        .build();
  }

  interface TestMethod {
    void run() throws IOException, URISyntaxException;
  }

  public static class MockInstrumentationListener
      implements DebuggerTransformer.InstrumentationListener {
    public final Map<String, InstrumentationResult> results = new HashMap<>();

    @Override
    public void instrumentationResult(ProbeDefinition definition, InstrumentationResult result) {
      results.put(definition.getId(), result);
    }
  }

  static class KotlinHelper {
    public static Class<?> compileAndLoad(
        String className, String sourceFileName, List<File> outputFilesToDelete) {
      K2JVMCompiler compiler = new K2JVMCompiler();
      K2JVMCompilerArguments args = compiler.createArguments();
      args.setFreeArgs(Collections.singletonList(sourceFileName));
      String compilerOutputDir = "/tmp/" + CapturedSnapshotTest.class.getSimpleName() + "-kotlin";
      args.setDestination(compilerOutputDir);
      args.setClasspath(System.getProperty("java.class.path"));
      @EnabledForJreRange(
          max = JRE.JAVA_25) // TODO: Fix for Java 26. Delete once Java 26 is officially released.
      ExitCode exitCode =
          compiler.exec(
              new PrintingMessageCollector(System.out, MessageRenderer.WITHOUT_PATHS, true),
              Services.EMPTY,
              args);

      if (exitCode.getCode() != 0) {
        throw new RuntimeException("Kotlin compilation failed");
      }
      File compileOutputDirFile = new File(compilerOutputDir);
      try {
        URLClassLoader urlClassLoader =
            new URLClassLoader(new URL[] {compileOutputDirFile.toURI().toURL()});
        return urlClassLoader.loadClass(className);
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      } finally {
        registerFilesToDeleteDir(compileOutputDirFile, outputFilesToDelete);
      }
    }

    public static void registerFilesToDeleteDir(File dir, List<File> outputFilesToDelete) {
      if (!dir.exists()) {
        return;
      }
      try {
        Files.walk(dir.toPath())
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(outputFilesToDelete::add);
      } catch (IOException ex) {
        ex.printStackTrace();
      }
    }
  }
}
