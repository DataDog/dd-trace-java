package com.datadog.debugger.agent;

import static com.datadog.debugger.util.MoshiSnapshotHelper.ARGUMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.CAPTURES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.CAPTURE_EXPRESSIONS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.COLLECTION_SIZE_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.DEPTH_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ELEMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ENTRIES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ENTRY;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELDS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELD_COUNT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.IS_NULL;
import static com.datadog.debugger.util.MoshiSnapshotHelper.LOCALS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.RETURN;
import static com.datadog.debugger.util.MoshiSnapshotHelper.SIZE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.STATIC_FIELDS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.THIS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TIMEOUT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TRUNCATED;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TYPE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotHelper;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import datadog.trace.test.util.Flaky;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

public class SnapshotSerializationTest {

  private static final int PROBE_VERSION = 42;
  private static final ProbeId PROBE_ID = new ProbeId("12fd-8490-c111-4374-ffde", PROBE_VERSION);
  private static final ProbeLocation PROBE_LOCATION =
      new ProbeLocation("java.lang.String", "indexOf", "String.java", Arrays.asList("12-15", "23"));

  @BeforeEach
  public void setup() {
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer());
  }

  @Test
  public void roundTripProbeDetails() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Assertions.assertEquals(PROBE_ID.getId(), deserializedSnapshot.getProbe().getId());
    Assertions.assertEquals(
        PROBE_ID.getVersion(), deserializedSnapshot.getProbe().getProbeId().getVersion());
    ProbeLocation location = deserializedSnapshot.getProbe().getLocation();
    Assertions.assertEquals(PROBE_LOCATION.getType(), location.getType());
    Assertions.assertEquals(PROBE_LOCATION.getFile(), location.getFile());
    Assertions.assertEquals(PROBE_LOCATION.getMethod(), location.getMethod());
    Assertions.assertEquals(PROBE_LOCATION.getLines(), location.getLines());
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_17)
  @DisabledIf("datadog.environment.JavaVirtualMachine#isJ9")
  public void roundTripCapturedValue() throws IOException, URISyntaxException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue normalValuedLocal =
        CapturedContext.CapturedValue.of("normalValuedLocal", String.class.getTypeName(), "foobar");
    CapturedContext.CapturedValue normalNullLocal =
        CapturedContext.CapturedValue.of("normalNullLocal", String.class.getTypeName(), null);
    // this object generates InaccessibleObjectException since JDK16 when extracting its fields
    CapturedContext.CapturedValue notCapturedLocal =
        CapturedContext.CapturedValue.of(
            "notCapturedLocal",
            OperatingSystemMXBean.class.getTypeName(),
            ManagementFactory.getOperatingSystemMXBean());
    context.addLocals(
        new CapturedContext.CapturedValue[] {normalValuedLocal, normalNullLocal, notCapturedLocal});
    context.evaluate(
        PROBE_ID.getId(),
        new ProbeImplementation.NoopProbeImplementation(PROBE_ID, PROBE_LOCATION),
        String.class.getTypeName(),
        -1,
        MethodLocation.EXIT);
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Map<String, CapturedContext.CapturedValue> locals =
        deserializedSnapshot.getCaptures().getReturn().getLocals();
    Assertions.assertEquals(3, locals.size());
    normalValuedLocal = locals.get("normalValuedLocal");
    Assertions.assertEquals("foobar", normalValuedLocal.getValue());
    Assertions.assertEquals(String.class.getTypeName(), normalValuedLocal.getType());
    assertNull(normalValuedLocal.getNotCapturedReason());
    normalNullLocal = locals.get("normalNullLocal");
    assertNull(normalNullLocal.getValue());
    Assertions.assertEquals(String.class.getTypeName(), normalNullLocal.getType());
    assertNull(normalNullLocal.getNotCapturedReason());
    notCapturedLocal = locals.get("notCapturedLocal");
    Map<String, CapturedContext.CapturedValue> notCapturedFields =
        (Map<String, CapturedContext.CapturedValue>) notCapturedLocal.getValue();
    CapturedContext.CapturedValue processLoadTicks = notCapturedFields.get("processLoadTicks");
    Assertions.assertEquals(
        "Field is not accessible: module jdk.management does not opens/exports to the current module",
        processLoadTicks.getNotCapturedReason());
    CapturedContext.CapturedValue systemLoadTicks = notCapturedFields.get("systemLoadTicks");
    Assertions.assertEquals(
        "Field is not accessible: module jdk.management does not opens/exports to the current module",
        systemLoadTicks.getNotCapturedReason());
    CapturedContext.CapturedValue containerMetrics = notCapturedFields.get("containerMetrics");
    Assertions.assertEquals(
        "Field is not accessible: module jdk.management does not opens/exports to the current module",
        containerMetrics.getNotCapturedReason());
  }

  @Test
  public void roundTripCaughtException() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    Snapshot.Captures captures = snapshot.getCaptures();
    captures.addCaughtException(
        new CapturedContext.CapturedThrowable(
            IllegalArgumentException.class.getTypeName(),
            "illegal argument",
            Arrays.asList(
                new CapturedStackFrame("f1", 12),
                new CapturedStackFrame("f2", 23),
                new CapturedStackFrame("f3", 34)),
            null));
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    List<CapturedStackFrame> stacktrace =
        deserializedSnapshot.getCaptures().getCaughtExceptions().get(0).getStacktrace();
    Assertions.assertEquals(3, stacktrace.size());
    assertCapturedFrame(stacktrace.get(0), "f1", 12);
    assertCapturedFrame(stacktrace.get(1), "f2", 23);
    assertCapturedFrame(stacktrace.get(2), "f3", 34);
  }

  @Test
  public void roundtripEntryReturn() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext entryCapturedContext = new CapturedContext();
    entryCapturedContext.addLocals(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("localInt", "int", "42")
        });
    snapshot.setEntry(entryCapturedContext);
    CapturedContext exitCapturedContext = new CapturedContext();
    exitCapturedContext.addLocals(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("localInt", "int", "42")
        });
    exitCapturedContext.addReturn(
        CapturedContext.CapturedValue.of(String.class.getTypeName(), "foo"));
    exitCapturedContext.addThrowable(new RuntimeException("Illegal argument"));
    snapshot.setExit(exitCapturedContext);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    CapturedContext entry = deserializedSnapshot.getCaptures().getEntry();
    CapturedContext exit = deserializedSnapshot.getCaptures().getReturn();
    Assertions.assertEquals(1, entry.getLocals().size());
    Assertions.assertEquals(42, entry.getLocals().get("localInt").getValue());
    Assertions.assertEquals(3, exit.getLocals().size());
    Assertions.assertEquals(42, exit.getLocals().get("localInt").getValue());
    Assertions.assertEquals("foo", exit.getLocals().get("@return").getValue());
    Assertions.assertEquals(
        "Illegal argument",
        ((HashMap<String, CapturedContext.CapturedValue>)
                exit.getLocals().get("@exception").getValue())
            .get("detailMessage")
            .getValue());
    Assertions.assertEquals(
        RuntimeException.class.getTypeName(), exit.getCapturedThrowable().getType());
    Assertions.assertEquals("Illegal argument", exit.getCapturedThrowable().getMessage());
  }

  @Test
  public void truncatedExceptionMessage() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext exitCapturedContext = new CapturedContext();
    String oneKB =
        "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123";
    String largeErrorMessage = oneKB + oneKB + oneKB + oneKB;
    exitCapturedContext.addThrowable(new RuntimeException(largeErrorMessage));
    snapshot.setExit(exitCapturedContext);
    String buffer = adapter.toJson(snapshot);
    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Assertions.assertEquals(
        2048,
        deserializedSnapshot
            .getCaptures()
            .getReturn()
            .getCapturedThrowable()
            .getMessage()
            .length());
  }

  @Test
  public void roundtripLines() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    Snapshot.Captures captures = snapshot.getCaptures();
    CapturedContext lineCapturedContext = new CapturedContext();
    lineCapturedContext.addLocals(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("localInt", "int", "42")
        });
    captures.addLine(24, lineCapturedContext);
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Map<Integer, CapturedContext> lines = deserializedSnapshot.getCaptures().getLines();
    Assertions.assertEquals(1, lines.size());
    Map<String, CapturedContext.CapturedValue> lineLocals = lines.get(24).getLocals();
    Assertions.assertEquals(1, lineLocals.size());
    Assertions.assertEquals(42, lineLocals.get("localInt").getValue());
  }

  static class AnotherClass {
    int anotherIntField = 11;
    String anotherStrField = "foobar";
  }

  static class ComplexClass {
    int complexIntField = 21;
    String complexStrField = "bar";
    AnotherClass complexObjField = new AnotherClass();
  }

  static class AllPrimitives {
    long l = 42_000_000_000L;
    int i = 42_000;
    byte b = 42;
    short sh = 420;
    char c = 'r';
    float f = 3.14F;
    double d = 2.612;
    boolean bool = true;
  }

  static class FieldHolder {
    private int intField = 42;
    private String strField = "foo";
    private ComplexClass objField = new ComplexClass();
    private ComplexClass nullField = null;
  }

  @Test
  public void primitives() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue objLocal =
        capturedValueDepth("objLocal", "Class", new AllPrimitives(), 3);
    context.addLocals(new CapturedContext.CapturedValue[] {objLocal});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> thisFields = getLocalsFromJson(buffer);
    Map<String, Object> objFieldJson = (Map<String, Object>) thisFields.get("objLocal");
    Map<String, Object> objFieldFields = (Map<String, Object>) objFieldJson.get(FIELDS);
    assertPrimitiveValue(objFieldFields, "l", "long", "42000000000");
    assertPrimitiveValue(objFieldFields, "i", "int", "42000");
    assertPrimitiveValue(objFieldFields, "b", "byte", "42");
    assertPrimitiveValue(objFieldFields, "sh", "short", "420");
    assertPrimitiveValue(objFieldFields, "c", "char", "r");
    assertPrimitiveValue(objFieldFields, "f", "float", "3.14");
    assertPrimitiveValue(objFieldFields, "d", "double", "2.612");
    assertPrimitiveValue(objFieldFields, "bool", "boolean", "true");
  }

  static class WellKnownClasses {
    Class<?> clazz = WellKnownClasses.class;
    Boolean bool = Boolean.TRUE;
    Long l = Long.valueOf(42L);
    BigDecimal bigDecimal = new BigDecimal("3.1415926");
    Duration duration = Duration.ofMillis(1234567890);
    LocalDateTime localDateTime = LocalDateTime.of(2023, 1, 17, 13, 31);
    UUID uuid = UUID.nameUUIDFromBytes("foobar".getBytes());
    AtomicLong atomicLong = new AtomicLong(123);
    URI uri = URI.create("https://www.datadoghq.com");
    Optional<Date> maybeDate = Optional.of(new Date(1700000000000L));
    Optional<Object> empty = Optional.empty();
    OptionalInt maybeInt = OptionalInt.of(42);
    OptionalDouble maybeDouble = OptionalDouble.of(3.14);
    OptionalLong maybeLong = OptionalLong.of(84);
    Exception ex = new IllegalArgumentException("invalid arg");
    StackTraceElement element = new StackTraceElement("Foo", "bar", "foo.java", 42);
    File file = new File("/tmp/foo");
    Path path = file.toPath();
    CompletableFuture<String> future = CompletableFuture.completedFuture("FutureCompleted!");
  }

  @Test
  public void wellKnownClasses() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue objLocal =
        capturedValueDepth(
            "objLocal", WellKnownClasses.class.getTypeName(), new WellKnownClasses(), 3);
    context.addLocals(new CapturedContext.CapturedValue[] {objLocal});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    Map<String, Object> objLocalJson = (Map<String, Object>) locals.get("objLocal");
    Map<String, Object> objLocalFields = (Map<String, Object>) objLocalJson.get(FIELDS);
    assertPrimitiveValue(
        objLocalFields,
        "clazz",
        Class.class.getTypeName(),
        "com.datadog.debugger.agent.SnapshotSerializationTest$WellKnownClasses");
    assertPrimitiveValue(objLocalFields, "bool", Boolean.class.getTypeName(), "true");
    assertPrimitiveValue(objLocalFields, "l", Long.class.getTypeName(), "42");
    assertPrimitiveValue(objLocalFields, "bigDecimal", BigDecimal.class.getTypeName(), "3.1415926");
    assertPrimitiveValue(
        objLocalFields, "duration", Duration.class.getTypeName(), "PT342H56M7.89S");
    assertPrimitiveValue(
        objLocalFields, "localDateTime", LocalDateTime.class.getTypeName(), "2023-01-17T13:31");
    assertPrimitiveValue(
        objLocalFields, "uuid", UUID.class.getTypeName(), "3858f622-30ac-3c91-9f30-0c664312c63f");
    assertPrimitiveValue(objLocalFields, "atomicLong", AtomicLong.class.getTypeName(), "123");
    assertPrimitiveValue(
        objLocalFields, "uri", URI.class.getTypeName(), "https://www.datadoghq.com");
    // maybeDate
    Map<String, Object> maybeDate = (Map<String, Object>) objLocalFields.get("maybeDate");
    assertComplexClass(maybeDate, Optional.class.getTypeName());
    Map<String, Object> maybeDateFields = (Map<String, Object>) maybeDate.get(FIELDS);
    assertPrimitiveValue(maybeDateFields, "value", Date.class.getTypeName(), "1700000000000");
    // empty
    Map<String, Object> empty = (Map<String, Object>) objLocalFields.get("empty");
    assertComplexClass(empty, Optional.class.getTypeName());
    Map<String, Object> emptyFields = (Map<String, Object>) empty.get(FIELDS);
    Map<String, Object> value = (Map<String, Object>) emptyFields.get("value");
    assertEquals(Object.class.getTypeName(), value.get(TYPE));
    assertTrue((Boolean) value.get(IS_NULL));
    // maybeInt
    Map<String, Object> maybeInt = (Map<String, Object>) objLocalFields.get("maybeInt");
    assertComplexClass(maybeInt, OptionalInt.class.getTypeName());
    Map<String, Object> maybeIntFields = (Map<String, Object>) maybeInt.get(FIELDS);
    assertPrimitiveValue(maybeIntFields, "value", "int", "42");
    // maybeDouble
    Map<String, Object> maybeDouble = (Map<String, Object>) objLocalFields.get("maybeDouble");
    assertComplexClass(maybeDouble, OptionalDouble.class.getTypeName());
    Map<String, Object> maybeDoubleFields = (Map<String, Object>) maybeDouble.get(FIELDS);
    assertPrimitiveValue(maybeDoubleFields, "value", "double", "3.14");
    // maybeLong
    Map<String, Object> maybeLong = (Map<String, Object>) objLocalFields.get("maybeLong");
    assertComplexClass(maybeLong, OptionalLong.class.getTypeName());
    Map<String, Object> maybeLongFields = (Map<String, Object>) maybeLong.get(FIELDS);
    assertPrimitiveValue(maybeLongFields, "value", "long", "84");
    // ex
    Map<String, Object> ex = (Map<String, Object>) objLocalFields.get("ex");
    assertComplexClass(ex, IllegalArgumentException.class.getTypeName());
    Map<String, Object> exFields = (Map<String, Object>) ex.get(FIELDS);
    assertPrimitiveValue(exFields, "detailMessage", String.class.getTypeName(), "invalid arg");
    Map<String, Object> stackTrace = (Map<String, Object>) exFields.get("stackTrace");
    Assertions.assertEquals(StackTraceElement[].class.getTypeName(), stackTrace.get(TYPE));
    Map<String, Object> element = (Map<String, Object>) objLocalFields.get("element");
    assertComplexClass(element, StackTraceElement.class.getTypeName());
    Map<String, Object> elementFields = (Map<String, Object>) element.get(FIELDS);
    assertPrimitiveValue(elementFields, "declaringClass", String.class.getTypeName(), "Foo");
    assertPrimitiveValue(elementFields, "methodName", String.class.getTypeName(), "bar");
    assertPrimitiveValue(elementFields, "fileName", String.class.getTypeName(), "foo.java");
    assertPrimitiveValue(elementFields, "lineNumber", Integer.class.getTypeName(), "42");
    // file
    assertPrimitiveValue(objLocalFields, "file", File.class.getTypeName(), "/tmp/foo");
    // path
    assertPrimitiveValue(objLocalFields, "path", "sun.nio.fs.UnixPath", "/tmp/foo");
    if (JavaVirtualMachine.isJavaVersionAtLeast(19)) {
      Map<String, Object> future = (Map<String, Object>) objLocalFields.get("future");
      assertComplexClass(future, CompletableFuture.class.getTypeName());
      Map<String, Object> futureFields = (Map<String, Object>) future.get(FIELDS);
      assertPrimitiveValue(futureFields, "result", String.class.getTypeName(), "FutureCompleted!");
    }
  }

  @Test
  public void objectArray() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue localObjArray =
        capturedValueDepth(
            "localObjArray", Object[].class.getTypeName(), new Object[] {"foo", null, 42}, 3);
    context.addLocals(new CapturedContext.CapturedValue[] {localObjArray});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    assertArrayItem(locals, "localObjArray", "foo", null, "42");
  }

  static class ObjetArrayClass {
    ComplexClass[] complexClasses = new ComplexClass[3];
  }

  @Test
  public void fieldObjectArray() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue localObj =
        capturedValueDepth(
            "localObj", ObjetArrayClass.class.getTypeName(), new ObjetArrayClass(), 3);
    context.addLocals(new CapturedContext.CapturedValue[] {localObj});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    Map<String, Object> localObjMap = (Map<String, Object>) locals.get("localObj");
    Map<String, Object> localObjFieldsMap = (Map<String, Object>) localObjMap.get(FIELDS);
    Map<String, Object> complexClasses =
        (Map<String, Object>) localObjFieldsMap.get("complexClasses");
    Assertions.assertEquals(
        "com.datadog.debugger.agent.SnapshotSerializationTest$ComplexClass[]",
        complexClasses.get(TYPE));
  }

  static class PrimitiveArrayClass {
    byte[] byteArray = new byte[] {1, 2, 3};
    short[] shortArray = new short[] {128, 129, 130};
    char[] charArray = new char[] {'a', 'b', 'c'};
    int[] intArray = new int[] {128_001, 128_002, 128_003};
    long[] longArray = new long[] {3_000_000_000L, 3_000_000_001L, 3_000_000_002L};
    boolean[] booleanArray = new boolean[] {true, false, true};
    float[] floatArray = new float[] {3.14F, 3.15F, 3.16F};
    double[] doubleArray = new double[] {2.612, 2.613, 2.614};
  }

  @Test
  public void fieldPrimitiveArray() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue localObj =
        capturedValueDepth(
            "localObj", PrimitiveArrayClass.class.getTypeName(), new PrimitiveArrayClass(), 3);
    context.addLocals(new CapturedContext.CapturedValue[] {localObj});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    Map<String, Object> localObjMap = (Map<String, Object>) locals.get("localObj");
    Map<String, Object> localObjFieldsMap = (Map<String, Object>) localObjMap.get(FIELDS);
    assertArrayItem(localObjFieldsMap, "byteArray", "1", "2", "3");
    assertArrayItem(localObjFieldsMap, "shortArray", "128", "129", "130");
    assertArrayItem(localObjFieldsMap, "charArray", "a", "b", "c");
    assertArrayItem(localObjFieldsMap, "intArray", "128001", "128002", "128003");
    assertArrayItem(localObjFieldsMap, "longArray", "3000000000", "3000000001", "3000000002");
    assertArrayItem(localObjFieldsMap, "booleanArray", "true", "false", "true");
    assertArrayItem(localObjFieldsMap, "floatArray", "3.14", "3.15", "3.16");
    assertArrayItem(localObjFieldsMap, "doubleArray", "2.612", "2.613", "2.614");
  }

  @Test
  public void staticFields() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue staticStr =
        CapturedContext.CapturedValue.of("staticStr", String.class.getTypeName(), "foo");
    context.addStaticFields(new CapturedContext.CapturedValue[] {staticStr});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> staticFields = getStaticFieldsFromJson(buffer);
    assertPrimitiveValue(staticFields, "staticStr", String.class.getTypeName(), "foo");
  }

  @Test
  public void depthLevel0() throws IOException, URISyntaxException {
    Map<String, Object> returnJson = doRefDepth(0);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    assertNotCaptured(arguments, "this", FieldHolder.class.getTypeName(), DEPTH_REASON);
    assertPrimitiveValue(arguments, "strArg", String.class.getTypeName(), null);
    assertPrimitiveValue(arguments, "intArg", "int", "0");
    assertPrimitiveValue(arguments, "objArg", ComplexClass.class.getTypeName(), null);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
    assertPrimitiveValue(locals, "intLocal", "int", "42");
    assertNotCaptured(locals, "objLocal", ComplexClass.class.getTypeName(), DEPTH_REASON);
  }

  @Test
  public void depthLevel1() throws IOException, URISyntaxException {
    Map<String, Object> returnJson = doRefDepth(1);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    Map<String, Object> thisArg = (Map<String, Object>) arguments.get(THIS);
    Map<String, Object> thisFields = (Map<String, Object>) thisArg.get(FIELDS);
    assertPrimitiveValue(thisFields, "strField", String.class.getTypeName(), "foo");
    assertPrimitiveValue(thisFields, "nullField", ComplexClass.class.getTypeName(), null);
    assertPrimitiveValue(thisFields, "intField", "int", "42");
    assertNotCaptured(thisFields, "objField", ComplexClass.class.getTypeName(), DEPTH_REASON);
    assertPrimitiveValue(arguments, "strArg", String.class.getTypeName(), null);
    assertPrimitiveValue(arguments, "intArg", "int", "0");
    assertPrimitiveValue(arguments, "objArg", ComplexClass.class.getTypeName(), null);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
    assertPrimitiveValue(locals, "intLocal", "int", "42");
    Map<String, Object> objLocal = (Map<String, Object>) locals.get("objLocal");
    Assertions.assertEquals(ComplexClass.class.getTypeName(), objLocal.get(TYPE));
    Map<String, Object> objLocalFields = (Map<String, Object>) objLocal.get(FIELDS);
    assertPrimitiveValue(objLocalFields, "complexIntField", "int", "21");
    assertPrimitiveValue(objLocalFields, "complexStrField", String.class.getTypeName(), "bar");
    assertNotCaptured(
        objLocalFields, "complexObjField", AnotherClass.class.getTypeName(), DEPTH_REASON);
    assertNotCaptured(
        objLocalFields, "complexObjField", AnotherClass.class.getTypeName(), DEPTH_REASON);
  }

  @Test
  public void depthLevel2() throws IOException, URISyntaxException {
    Map<String, Object> returnJson = doRefDepth(2);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    Map<String, Object> thisArg = (Map<String, Object>) arguments.get(THIS);
    Map<String, Object> thisFields = (Map<String, Object>) thisArg.get(FIELDS);
    assertPrimitiveValue(thisFields, "strField", String.class.getTypeName(), "foo");
    assertPrimitiveValue(thisFields, "nullField", ComplexClass.class.getTypeName(), null);
    assertPrimitiveValue(thisFields, "intField", "int", "42");
    Map<String, Object> objField = (Map<String, Object>) thisFields.get("objField");
    Assertions.assertEquals(ComplexClass.class.getTypeName(), objField.get(TYPE));
    Map<String, Object> objFieldFields = (Map<String, Object>) objField.get(FIELDS);
    assertPrimitiveValue(objFieldFields, "complexIntField", "int", "21");
    assertPrimitiveValue(objFieldFields, "complexStrField", String.class.getTypeName(), "bar");
    assertNotCaptured(
        objFieldFields, "complexObjField", AnotherClass.class.getTypeName(), DEPTH_REASON);
    assertPrimitiveValue(arguments, "strArg", String.class.getTypeName(), null);
    assertPrimitiveValue(arguments, "intArg", "int", "0");
    assertPrimitiveValue(arguments, "objArg", ComplexClass.class.getTypeName(), null);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
    assertPrimitiveValue(locals, "intLocal", "int", "42");
    Map<String, Object> objLocal = (Map<String, Object>) locals.get("objLocal");
    Assertions.assertEquals(ComplexClass.class.getTypeName(), objLocal.get("type"));
    Map<String, Object> objLocalFields = (Map<String, Object>) objLocal.get(FIELDS);
    assertPrimitiveValue(objLocalFields, "complexIntField", "int", "21");
    assertPrimitiveValue(objLocalFields, "complexStrField", String.class.getTypeName(), "bar");
    Map<String, Object> localComplexObjField =
        (Map<String, Object>) objLocalFields.get("complexObjField");
    Assertions.assertEquals(AnotherClass.class.getTypeName(), localComplexObjField.get(TYPE));
    Map<String, Object> localComplexObjFieldFields =
        (Map<String, Object>) localComplexObjField.get(FIELDS);
    assertPrimitiveValue(localComplexObjFieldFields, "anotherIntField", "int", "11");
    assertPrimitiveValue(
        localComplexObjFieldFields, "anotherStrField", String.class.getTypeName(), "foobar");
  }

  private Map<String, Object> doRefDepth(int maxRefDepth) throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshotForRefDepth(maxRefDepth);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    return (Map<String, Object>) capturesJson.get(RETURN);
  }

  @Test
  public void collectionSize0() throws IOException {
    Map<String, Object> locals = doCollectionSize(0);
    assertNotCaptured(locals, "intArrayLocal", "int[]", COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbElements(locals, "intArrayLocal"));
    assertNotCaptured(
        locals, "strArrayLocal", String[].class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbElements(locals, "strArrayLocal"));
    assertNotCaptured(
        locals, "objArrayLocal", Object[].class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbElements(locals, "objArrayLocal"));
    assertNotCaptured(locals, "listLocal", ArrayList.class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbElements(locals, "listLocal"));
    assertNotCaptured(locals, "mapLocal", HashMap.class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbEntries(locals, "mapLocal"));
  }

  @Test
  public void collectionSize3() throws IOException {
    Map<String, Object> locals = doCollectionSize(3);
    assertNotCaptured(locals, "intArrayLocal", "int[]", COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbElements(locals, "intArrayLocal"));
    assertArrayItem(locals, "intArrayLocal", "0", "1", "2");
    assertNotCaptured(
        locals, "strArrayLocal", String[].class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbElements(locals, "strArrayLocal"));
    assertArrayItem(locals, "strArrayLocal", "foo0", "foo1", "foo2");
    assertNotCaptured(
        locals, "objArrayLocal", Object[].class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbElements(locals, "objArrayLocal"));
    List<Object> objArrayElements = getArrayElements(locals, "objArrayLocal");
    assertComplexClass(objArrayElements.get(0), ComplexClass.class.getTypeName());
    assertComplexClass(objArrayElements.get(1), ComplexClass.class.getTypeName());
    assertComplexClass(objArrayElements.get(2), ComplexClass.class.getTypeName());
    assertNotCaptured(locals, "listLocal", ArrayList.class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbElements(locals, "listLocal"));
    assertArrayItem(locals, "listLocal", "foo0", "foo1", "foo2");
    assertNotCaptured(locals, "mapLocal", HashMap.class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbEntries(locals, "mapLocal"));
  }

  @Test
  public void collectionSize100() throws IOException {
    Map<String, Object> locals = doCollectionSize(100);
    assertNotCaptured(locals, "intArrayLocal", "int[]", null);
    Assertions.assertEquals(10, getNbElements(locals, "intArrayLocal"));
    assertArrayItem(locals, "intArrayLocal", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    assertNotCaptured(locals, "strArrayLocal", String[].class.getTypeName(), null);
    Assertions.assertEquals(10, getNbElements(locals, "strArrayLocal"));
    assertArrayItem(
        locals,
        "strArrayLocal",
        "foo0",
        "foo1",
        "foo2",
        "foo3",
        "foo4",
        "foo5",
        "foo6",
        "foo7",
        "foo8",
        "foo9");
    assertNotCaptured(locals, "objArrayLocal", Object[].class.getTypeName(), null);
    Assertions.assertEquals(10, getNbElements(locals, "objArrayLocal"));
    List<Object> objArrayElements = getArrayElements(locals, "objArrayLocal");
    assertComplexClass(objArrayElements.get(0), ComplexClass.class.getTypeName());
    assertComplexClass(objArrayElements.get(4), ComplexClass.class.getTypeName());
    assertComplexClass(objArrayElements.get(9), ComplexClass.class.getTypeName());
    assertNotCaptured(locals, "listLocal", ArrayList.class.getTypeName(), null);
    Assertions.assertEquals(10, getNbElements(locals, "listLocal"));
    assertArrayItem(
        locals,
        "listLocal",
        "foo0",
        "foo1",
        "foo2",
        "foo3",
        "foo4",
        "foo5",
        "foo6",
        "foo7",
        "foo8",
        "foo9");
    assertNotCaptured(locals, "mapLocal", HashMap.class.getTypeName(), null);
    Assertions.assertEquals(10, getNbEntries(locals, "mapLocal"));
    assertMapItems(
        locals,
        "mapLocal",
        "foo0",
        "bar0",
        "foo1",
        "bar1",
        "foo2",
        "bar2",
        "foo3",
        "bar3",
        "foo4",
        "bar4",
        "foo5",
        "bar5",
        "foo6",
        "bar6",
        "foo7",
        "bar7",
        "foo8",
        "bar8",
        "foo9",
        "bar9");
  }

  private Map<String, Object> doCollectionSize(int maxColSize) throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshotForCollectionSize(maxColSize);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getLocalsFromJson(buffer);
  }

  @Test
  public void map0() throws IOException {
    Map<String, Object> locals = doMapSize(0);
    assertNotCaptured(locals, "strMap", HashMap.class.getTypeName(), COLLECTION_SIZE_REASON);
    assertMapItems(locals, "strMap");
    assertSize(locals, "strMap", "10");
  }

  @Test
  public void map3() throws IOException {
    Map<String, Object> locals = doMapSize(3);
    assertNotCaptured(locals, "strMap", HashMap.class.getTypeName(), COLLECTION_SIZE_REASON);
    Map<String, Object> field = (Map<String, Object>) locals.get("strMap");
    List<Object> entries = (List<Object>) field.get(ENTRIES);
    Assertions.assertEquals(3, entries.size());
    assertSize(locals, "strMap", "10");
  }

  @Test
  public void map100() throws IOException {
    Map<String, Object> locals = doMapSize(100);
    assertNotCaptured(locals, "strMap", HashMap.class.getTypeName(), null);
    assertMapItems(
        locals, "strMap", "foo0", "bar0", "foo1", "bar1", "foo2", "bar2", "foo3", "bar3", "foo4",
        "bar4", "foo5", "bar5", "foo6", "bar6", "foo7", "bar7", "foo8", "bar8", "foo9", "bar9");
    assertSize(locals, "strMap", "10");
  }

  private Map<String, Object> doMapSize(int maxColSize) throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshotForMapSize(maxColSize);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getLocalsFromJson(buffer);
  }

  @Test
  public void length0() throws IOException {
    Map<String, Object> locals = doLength(0);
    assertPrimitiveValue(locals, "strLocal", String.class.getTypeName(), "");
    assertTruncated(locals, "strLocal", String.class.getTypeName(), "true");
    assertSize(locals, "strLocal", "10");
  }

  @Test
  public void length3() throws IOException {
    Map<String, Object> locals = doLength(3);
    assertPrimitiveValue(locals, "strLocal", String.class.getTypeName(), "012");
    assertTruncated(locals, "strLocal", String.class.getTypeName(), "true");
    assertSize(locals, "strLocal", "10");
  }

  @Test
  public void length255() throws IOException {
    Map<String, Object> locals = doLength(255);
    assertPrimitiveValue(locals, "strLocal", String.class.getTypeName(), "0123456789");
    assertTruncated(locals, "strLocal", String.class.getTypeName(), "null");
    assertSize(locals, "strLocal", "null"); // no size field if no truncation
  }

  @Test
  public void capturesAdapterNull() {
    MoshiSnapshotHelper.CapturesAdapter capturesAdapter =
        new MoshiSnapshotHelper.CapturesAdapter(MoshiHelper.createMoshiSnapshot(), null);
    Assertions.assertEquals("null", capturesAdapter.toJson(null));
  }

  @Test
  public void capturedValueAdapterNull() {
    MoshiSnapshotHelper.CapturedValueAdapter capturedValueAdapter =
        new MoshiSnapshotHelper.CapturedValueAdapter();
    Assertions.assertEquals("null", capturedValueAdapter.toJson(null));
  }

  @Test
  public void collectionUnknown() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue listLocal =
        CapturedContext.CapturedValue.of(
            "listLocal", List.class.getTypeName(), new ArrayList<String>() {});
    context.addLocals(new CapturedContext.CapturedValue[] {listLocal});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    Map<String, Object> listLocalField = (Map<String, Object>) locals.get("listLocal");
    Map<String, Object> listLocalFieldFields = (Map<String, Object>) listLocalField.get(FIELDS);
    assertTrue(listLocalFieldFields.containsKey("elementData"));
    assertTrue(listLocalFieldFields.containsKey("size"));
    assertTrue(listLocalFieldFields.containsKey("modCount"));
  }

  @Test
  public void mapValueUnknown() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue mapLocal =
        CapturedContext.CapturedValue.of(
            "mapLocal", Map.class.getTypeName(), new HashMap<String, String>() {});
    context.addLocals(new CapturedContext.CapturedValue[] {mapLocal});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    Map<String, Object> mapLocalField = (Map<String, Object>) locals.get("mapLocal");
    Map<String, Object> mapLocalFieldFields = (Map<String, Object>) mapLocalField.get(FIELDS);
    assertTrue(mapLocalFieldFields.containsKey("table"));
    assertTrue(mapLocalFieldFields.containsKey("size"));
    assertTrue(mapLocalFieldFields.containsKey("threshold"));
    assertTrue(mapLocalFieldFields.containsKey("loadFactor"));
  }

  @Test
  public void mapNullValue() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    Map<String, String> map = new HashMap<>();
    map.put("foo", null);
    CapturedContext.CapturedValue mapLocal =
        CapturedContext.CapturedValue.of("mapLocal", Map.class.getTypeName(), map);
    context.addLocals(new CapturedContext.CapturedValue[] {mapLocal});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    List<Object> entries = getMapEntries(locals, "mapLocal");
    assertEquals(1, entries.size());
    List<Object> entry = (List<Object>) entries.get(0);
    assertEquals(2, entry.size());
    Map<String, Object> value = (Map<String, Object>) entry.get(1);
    assertEquals(Object.class.getTypeName(), value.get(TYPE));
    assertTrue((Boolean) value.get(IS_NULL));
  }

  @Test
  public void listNullValue() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    List<String> list = new ArrayList<>();
    list.add("foo");
    list.add(null);
    list.add("bar");
    CapturedContext.CapturedValue listLocal =
        CapturedContext.CapturedValue.of("listLocal", List.class.getTypeName(), list);
    context.addLocals(new CapturedContext.CapturedValue[] {listLocal});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    List<Object> elements = getArrayElements(locals, "listLocal");
    Map<String, Object> nullElement = (Map<String, Object>) elements.get(1);
    assertEquals(Object.class.getTypeName(), nullElement.get(TYPE));
    assertTrue((Boolean) nullElement.get(IS_NULL));
  }

  private Map<String, Object> doLength(int maxLength) throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshotForLength(maxLength);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getLocalsFromJson(buffer);
  }

  @Test
  public void fieldCount0() throws IOException {
    assertNull(doFieldCount(0));
  }

  @Test
  public void fieldCount3() throws IOException {
    Map<String, Object> thisArg = doFieldCount(3);
    Map<String, CapturedContext.CapturedValue> fields =
        (Map<String, CapturedContext.CapturedValue>) thisArg.get(FIELDS);
    Assertions.assertEquals(3, fields.size());
    Assertions.assertEquals(FIELD_COUNT_REASON, thisArg.get(NOT_CAPTURED_REASON));
  }

  @Test
  public void fieldCount20() throws IOException {
    Map<String, Object> thisArg = doFieldCount(20);
    Assertions.assertEquals(
        4, ((Map<String, CapturedContext.CapturedValue>) thisArg.get(FIELDS)).size());
    assertNull(thisArg.get(NOT_CAPTURED_REASON));
  }

  @Test
  @Flaky
  public void timeOut() throws IOException {
    DebuggerContext.initValueSerializer(
        new TimeoutSnapshotSerializer(Duration.of(150, ChronoUnit.MILLIS)));
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue arg1 = CapturedContext.CapturedValue.of("arg1", "int", 42);
    CapturedContext.CapturedValue arg2 = CapturedContext.CapturedValue.of("arg2", "int", 42);
    CapturedContext.CapturedValue arg3 = CapturedContext.CapturedValue.of("arg3", "int", 42);
    context.addArguments(new CapturedContext.CapturedValue[] {arg1, arg2, arg3});
    snapshot.setEntry(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println("timeout: " + buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> entryJson = (Map<String, Object>) capturesJson.get(ENTRY);
    Assertions.assertEquals(TIMEOUT_REASON, entryJson.get(NOT_CAPTURED_REASON));
  }

  @Test
  @Flaky
  public void valueTimeout() throws IOException {
    DebuggerContext.initValueSerializer(
        new TimeoutSnapshotSerializer(Duration.of(20, ChronoUnit.MILLIS)));
    CapturedContext.CapturedValue arg1 =
        CapturedContext.CapturedValue.of("arg1", Random.class.getTypeName(), new Random(0));
    arg1.freeze(new TimeoutChecker(Duration.ofMillis(10)));
    String buffer = arg1.getStrValue();
    System.out.println(buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Assertions.assertEquals(TIMEOUT_REASON, json.get(NOT_CAPTURED_REASON));
  }

  enum MyEnum {
    ONE,
    TWO,
    THREE;
  }

  @Test
  public void enumValues() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue enumValue =
        CapturedContext.CapturedValue.of("enumValue", MyEnum.class.getTypeName(), MyEnum.TWO);
    context.addLocals(new CapturedContext.CapturedValue[] {enumValue});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> locals = getLocalsFromJson(buffer);
    Map<String, Object> enumValueJson = (Map<String, Object>) locals.get("enumValue");
    assertEquals("TWO", enumValueJson.get("value"));
  }

  @Test
  public void captureExpressions() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    Map<String, String> map = new HashMap<>();
    map.put("foo1", "bar1");
    map.put("foo2", "bar2");
    map.put("foo3", "bar3");
    context.addCaptureExpression(
        CapturedContext.CapturedValue.of("expr1", Map.class.getTypeName(), map));
    context.addCaptureExpression(
        CapturedContext.CapturedValue.of(
            "expr2", List.class.getTypeName(), Arrays.asList("1", "2", "3")));
    context.addCaptureExpression(
        CapturedContext.CapturedValue.of("expr3", Integer.TYPE.getTypeName(), 42));
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    Map<String, Object> captureExpressions =
        (Map<String, Object>) returnJson.get(CAPTURE_EXPRESSIONS);
    assertNull(returnJson.get(LOCALS));
    assertNull(returnJson.get(ARGUMENTS));
    assertEquals(3, captureExpressions.size());
    assertMapItems(captureExpressions, "expr1", "foo1", "bar1", "foo2", "bar2", "foo3", "bar3");
    assertArrayItem(captureExpressions, "expr2", "1", "2", "3");
    assertPrimitiveValue(captureExpressions, "expr3", Integer.TYPE.getTypeName(), "42");
  }

  private Map<String, Object> doFieldCount(int maxFieldCount) throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshotForFieldCount(maxFieldCount);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getThisFromJson(buffer);
  }

  private void assertComplexClass(Object obj, String type) {
    Map<String, Object> objMap = (Map<String, Object>) obj;
    Assertions.assertEquals(type, objMap.get(TYPE));
    Assertions.assertTrue(objMap.containsKey(FIELDS));
  }

  private int getNbElements(Map<String, Object> item, String name) {
    List<Object> elements = getArrayElements(item, name);
    return elements.size();
  }

  private int getNbEntries(Map<String, Object> item, String name) {
    List<Object> entries = getMapEntries(item, name);
    return entries.size();
  }

  private void assertArrayItem(Map<String, Object> item, String name, String... values) {
    List<Object> elements = getArrayElements(item, name);
    for (int i = 0; i < values.length; i++) {
      Map<String, Object> elem = (Map<String, Object>) elements.get(i);
      Assertions.assertEquals(values[i], elem.get(VALUE));
    }
  }

  private List<Object> getArrayElements(Map<String, Object> item, String name) {
    Map<String, Object> array = (Map<String, Object>) item.get(name);
    return (List<Object>) array.get(ELEMENTS);
  }

  private List<Object> getMapEntries(Map<String, Object> item, String name) {
    Map<String, Object> array = (Map<String, Object>) item.get(name);
    return (List<Object>) array.get(ENTRIES);
  }

  private void assertMapItems(Map<String, Object> item, String name, String... expectedValues) {
    Map<String, Object> field = (Map<String, Object>) item.get(name);
    List<Object> entries = (List<Object>) field.get(ENTRIES);
    Assertions.assertEquals(expectedValues.length / 2, entries.size());
    int index = 0;
    Map<String, String> tmpMap = new HashMap<>();
    for (Object obj : entries) {
      List<Object> entry = (List<Object>) obj;
      Map<String, Object> key = (Map<String, Object>) entry.get(0);
      Map<String, Object> value = (Map<String, Object>) entry.get(1);
      tmpMap.put((String) key.get(VALUE), (String) value.get(VALUE));
    }
    index = 0;
    while (index < expectedValues.length) {
      String key = expectedValues[index++];
      String value = expectedValues[index++];
      Assertions.assertTrue(tmpMap.containsKey(key));
      Assertions.assertEquals(value, tmpMap.get(key));
    }
  }

  private void assertNotCaptured(
      Map<String, Object> item, String name, String type, String reason) {
    Map<String, Object> obj = (Map<String, Object>) item.get(name);
    Assertions.assertEquals(type, obj.get(TYPE));
    Assertions.assertEquals(reason, obj.get(NOT_CAPTURED_REASON));
  }

  private void assertTruncated(
      Map<String, Object> item, String name, String type, String truncated) {
    Map<String, Object> obj = (Map<String, Object>) item.get(name);
    Assertions.assertEquals(type, obj.get(TYPE));
    Assertions.assertEquals(truncated, String.valueOf(obj.get(TRUNCATED)));
  }

  private void assertSize(Map<String, Object> item, String name, String size) {
    Map<String, Object> obj = (Map<String, Object>) item.get(name);
    Assertions.assertEquals(size, String.valueOf(obj.get(SIZE)));
  }

  private void assertPrimitiveValue(
      Map<String, Object> item, String name, String type, String value) {
    Map<String, Object> prim = (Map<String, Object>) item.get(name);
    Assertions.assertEquals(type, prim.get(TYPE));
    if (value != null) {
      Assertions.assertEquals(value, String.valueOf(prim.get(VALUE)));
    } else {
      Assertions.assertEquals(true, prim.get(IS_NULL));
    }
  }

  private Map<String, Object> getLocalsFromJson(String buffer) throws IOException {
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    return (Map<String, Object>) returnJson.get(LOCALS);
  }

  private Map<String, Object> getFieldsFromJson(String buffer) throws IOException {
    Map<String, Object> thisArg = getThisFromJson(buffer);
    return (Map<String, Object>) thisArg.get(FIELDS);
  }

  private Map<String, Object> getStaticFieldsFromJson(String buffer) throws IOException {
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    return (Map<String, Object>) returnJson.get(STATIC_FIELDS);
  }

  private Map<String, Object> getThisFromJson(String buffer) throws IOException {
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    return (Map<String, Object>) arguments.get(THIS);
  }

  private Snapshot createSnapshotForRefDepth(int maxRefDepth) {
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue fieldHolder =
        capturedValueDepth(THIS, FieldHolder.class.getTypeName(), new FieldHolder(), maxRefDepth);
    context.addArguments(new CapturedContext.CapturedValue[] {fieldHolder});
    CapturedContext.CapturedValue intArg = capturedValueDepth("intArg", "int", 0, maxRefDepth);
    CapturedContext.CapturedValue strArg =
        capturedValueDepth("strArg", String.class.getTypeName(), null, maxRefDepth);
    CapturedContext.CapturedValue objArg =
        capturedValueDepth("objArg", ComplexClass.class.getTypeName(), null, maxRefDepth);
    context.addArguments(new CapturedContext.CapturedValue[] {intArg, strArg, objArg});
    CapturedContext.CapturedValue intLocal = capturedValueDepth("intLocal", "int", 42, maxRefDepth);
    CapturedContext.CapturedValue objLocal =
        capturedValueDepth(
            "objLocal", ComplexClass.class.getTypeName(), new ComplexClass(), maxRefDepth);
    context.addLocals(new CapturedContext.CapturedValue[] {intLocal, objLocal});
    snapshot.setExit(context);
    return snapshot;
  }

  private Snapshot createSnapshotForCollectionSize(int maxColSize) {
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue intArrayLocal =
        capturedValueColSize(
            "intArrayLocal", "int[]", new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, maxColSize);
    CapturedContext.CapturedValue strArrayLocal =
        capturedValueColSize(
            "strArrayLocal",
            String[].class.getTypeName(),
            new String[] {
              "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8", "foo9"
            },
            maxColSize);
    CapturedContext.CapturedValue objArrayLocal =
        capturedValueColSize(
            "objArrayLocal",
            Object[].class.getTypeName(),
            new Object[] {
              new ComplexClass(),
              new ComplexClass(),
              new ComplexClass(),
              new ComplexClass(),
              new ComplexClass(),
              new ComplexClass(),
              new ComplexClass(),
              new ComplexClass(),
              new ComplexClass(),
              new ComplexClass()
            },
            maxColSize);
    CapturedContext.CapturedValue listLocal =
        capturedValueColSize(
            "listLocal",
            List.class.getTypeName(),
            new ArrayList<>(
                Arrays.asList(
                    "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8",
                    "foo9")),
            maxColSize);
    Map<String, String> mapObj = new HashMap<>();
    mapObj.put("foo0", "bar0");
    mapObj.put("foo1", "bar1");
    mapObj.put("foo2", "bar2");
    mapObj.put("foo3", "bar3");
    mapObj.put("foo4", "bar4");
    mapObj.put("foo5", "bar5");
    mapObj.put("foo6", "bar6");
    mapObj.put("foo7", "bar7");
    mapObj.put("foo8", "bar8");
    mapObj.put("foo9", "bar9");
    CapturedContext.CapturedValue mapLocal =
        capturedValueColSize("mapLocal", Map.class.getTypeName(), mapObj, maxColSize);
    context.addLocals(
        new CapturedContext.CapturedValue[] {
          intArrayLocal, strArrayLocal, objArrayLocal, listLocal, mapLocal
        });
    snapshot.setExit(context);
    return snapshot;
  }

  private Snapshot createSnapshotForMapSize(int maxColSize) {
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    HashMap<String, String> strMap = new HashMap<>();
    strMap.put("foo0", "bar0");
    strMap.put("foo1", "bar1");
    strMap.put("foo2", "bar2");
    strMap.put("foo3", "bar3");
    strMap.put("foo4", "bar4");
    strMap.put("foo5", "bar5");
    strMap.put("foo6", "bar6");
    strMap.put("foo7", "bar7");
    strMap.put("foo8", "bar8");
    strMap.put("foo9", "bar9");
    CapturedContext.CapturedValue map =
        CapturedContext.CapturedValue.of(
            "strMap",
            strMap.getClass().getTypeName(),
            strMap,
            Limits.DEFAULT_REFERENCE_DEPTH,
            maxColSize,
            Limits.DEFAULT_LENGTH,
            Limits.DEFAULT_FIELD_COUNT);
    context.addLocals(new CapturedContext.CapturedValue[] {map});
    snapshot.setExit(context);
    return snapshot;
  }

  private Snapshot createSnapshotForLength(int maxLength) {
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue strLocal =
        CapturedContext.CapturedValue.of(
            "strLocal",
            String.class.getTypeName(),
            "0123456789",
            Limits.DEFAULT_REFERENCE_DEPTH,
            Limits.DEFAULT_COLLECTION_SIZE,
            maxLength,
            Limits.DEFAULT_FIELD_COUNT);
    context.addLocals(new CapturedContext.CapturedValue[] {strLocal});
    snapshot.setExit(context);
    return snapshot;
  }

  private Snapshot createSnapshotForFieldCount(int maxFieldCount) {
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    context.setLimits(
        Limits.DEFAULT_REFERENCE_DEPTH,
        Limits.DEFAULT_COLLECTION_SIZE,
        Limits.DEFAULT_LENGTH,
        maxFieldCount);
    CapturedContext.CapturedValue fieldHolder =
        capturedValueFieldCount(
            THIS, FieldHolder.class.getTypeName(), new FieldHolder(), maxFieldCount);
    context.addArguments(new CapturedContext.CapturedValue[] {fieldHolder});
    snapshot.setExit(context);
    return snapshot;
  }

  private CapturedContext.CapturedValue capturedValueDepth(
      String name, String type, Object value, int maxDepth) {
    return CapturedContext.CapturedValue.of(
        name,
        type,
        value,
        maxDepth,
        Limits.DEFAULT_COLLECTION_SIZE,
        Limits.DEFAULT_LENGTH,
        Limits.DEFAULT_FIELD_COUNT);
  }

  private CapturedContext.CapturedValue capturedValueColSize(
      String name, String type, Object value, int maxColSize) {
    return CapturedContext.CapturedValue.of(
        name,
        type,
        value,
        Limits.DEFAULT_REFERENCE_DEPTH,
        maxColSize,
        Limits.DEFAULT_LENGTH,
        Limits.DEFAULT_FIELD_COUNT);
  }

  private CapturedContext.CapturedValue capturedValueFieldCount(
      String name, String type, Object value, int maxFieldCount) {
    return CapturedContext.CapturedValue.of(
        name,
        type,
        value,
        Limits.DEFAULT_REFERENCE_DEPTH,
        Limits.DEFAULT_COLLECTION_SIZE,
        Limits.DEFAULT_LENGTH,
        maxFieldCount);
  }

  private void assertCapturedFrame(
      CapturedStackFrame capturedStackFrame, String methodName, int lineNumber) {
    assertNull(capturedStackFrame.getFileName());
    Assertions.assertEquals(methodName, capturedStackFrame.getFunction());
    Assertions.assertEquals(lineNumber, capturedStackFrame.getLineNumber());
  }

  private Snapshot createSnapshot() {
    return new Snapshot(
        Thread.currentThread(),
        new ProbeImplementation.NoopProbeImplementation(PROBE_ID, PROBE_LOCATION),
        Limits.DEFAULT_REFERENCE_DEPTH);
  }

  private static JsonAdapter<Snapshot> createSnapshotAdapter() {
    return MoshiSnapshotTestHelper.createMoshiSnapshot().adapter(Snapshot.class);
  }

  private static class TimeoutSnapshotSerializer implements DebuggerContext.ValueSerializer {
    private final JsonAdapter<CapturedContext.CapturedValue> VALUE_ADAPTER =
        new MoshiSnapshotHelper.CapturedValueAdapter();
    private final Duration sleepTime;

    public TimeoutSnapshotSerializer(Duration sleepTime) {
      this.sleepTime = sleepTime;
    }

    @Override
    public String serializeValue(CapturedContext.CapturedValue value) {
      LockSupport.parkNanos(sleepTime.toNanos());
      return VALUE_ADAPTER.toJson(value);
    }
  }
}
