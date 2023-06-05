package com.datadog.debugger.agent;

import static com.datadog.debugger.util.MoshiSnapshotHelper.ARGUMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.CAPTURES;
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
import static com.datadog.debugger.util.MoshiSnapshotHelper.THIS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TIMEOUT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TRUNCATED;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TYPE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.VALUE;

import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotHelper;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.squareup.moshi.JsonAdapter;
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
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
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
  @EnabledOnJre(JRE.JAVA_17)
  public void roundTripCapturedValue() throws IOException, URISyntaxException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue normalValuedField =
        CapturedContext.CapturedValue.of("normalValuedField", String.class.getTypeName(), "foobar");
    CapturedContext.CapturedValue normalNullField =
        CapturedContext.CapturedValue.of("normalNullField", String.class.getTypeName(), null);
    // this object generates InaccessibleObjectException since JDK16 when extracting its fields
    CapturedContext.CapturedValue notCapturedField =
        CapturedContext.CapturedValue.of(
            "notCapturedField",
            OperatingSystemMXBean.class.getTypeName(),
            ManagementFactory.getOperatingSystemMXBean());
    context.addFields(
        new CapturedContext.CapturedValue[] {normalValuedField, normalNullField, notCapturedField});
    context.evaluate(
        PROBE_ID.getId(),
        new ProbeImplementation.NoopProbeImplementation(PROBE_ID, PROBE_LOCATION),
        String.class.getTypeName(),
        -1,
        MethodLocation.EXIT);
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Map<String, CapturedContext.CapturedValue> fields =
        deserializedSnapshot.getCaptures().getReturn().getFields();
    Assertions.assertEquals(3, fields.size());
    normalValuedField = fields.get("normalValuedField");
    Assertions.assertEquals("foobar", normalValuedField.getValue());
    Assertions.assertEquals(String.class.getTypeName(), normalValuedField.getType());
    Assertions.assertNull(normalValuedField.getNotCapturedReason());
    normalNullField = fields.get("normalNullField");
    Assertions.assertNull(normalNullField.getValue());
    Assertions.assertEquals(String.class.getTypeName(), normalNullField.getType());
    Assertions.assertNull(normalNullField.getNotCapturedReason());
    notCapturedField = fields.get("notCapturedField");
    Map<String, CapturedContext.CapturedValue> notCapturedFields =
        (Map<String, CapturedContext.CapturedValue>) notCapturedField.getValue();
    CapturedContext.CapturedValue processLoadTicks = notCapturedFields.get("processLoadTicks");
    Assertions.assertTrue(
        processLoadTicks
            .getNotCapturedReason()
            .startsWith(
                "java.lang.reflect.InaccessibleObjectException: Unable to make field private com.sun.management.internal.OperatingSystemImpl$ContainerCpuTicks com.sun.management.internal.OperatingSystemImpl.processLoadTicks accessible: module jdk.management does not \"opens com.sun.management.internal\" to unnamed module @"));
    CapturedContext.CapturedValue systemLoadTicks = notCapturedFields.get("systemLoadTicks");
    Assertions.assertTrue(
        systemLoadTicks
            .getNotCapturedReason()
            .startsWith(
                "java.lang.reflect.InaccessibleObjectException: Unable to make field private com.sun.management.internal.OperatingSystemImpl$ContainerCpuTicks com.sun.management.internal.OperatingSystemImpl.systemLoadTicks accessible: module jdk.management does not \"opens com.sun.management.internal\" to unnamed module @"));
    CapturedContext.CapturedValue containerMetrics = notCapturedFields.get("containerMetrics");
    Assertions.assertTrue(
        containerMetrics
            .getNotCapturedReason()
            .startsWith(
                "java.lang.reflect.InaccessibleObjectException: Unable to make field private final jdk.internal.platform.Metrics com.sun.management.internal.OperatingSystemImpl.containerMetrics accessible: module jdk.management does not \"opens com.sun.management.internal\" to unnamed module @"));
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
                new CapturedStackFrame("f3", 34))));
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
    Snapshot.Captures captures = snapshot.getCaptures();
    CapturedContext entryCapturedContext = new CapturedContext();
    entryCapturedContext.addFields(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("fieldInt", "int", "42")
        });
    snapshot.setEntry(entryCapturedContext);
    CapturedContext exitCapturedContext = new CapturedContext();
    exitCapturedContext.addFields(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("fieldInt", "int", "42")
        });
    exitCapturedContext.addReturn(
        CapturedContext.CapturedValue.of(String.class.getTypeName(), "foo"));
    snapshot.setExit(exitCapturedContext);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    CapturedContext entry = deserializedSnapshot.getCaptures().getEntry();
    CapturedContext exit = deserializedSnapshot.getCaptures().getReturn();
    Assertions.assertEquals(1, entry.getFields().size());
    Assertions.assertEquals(42, entry.getFields().get("fieldInt").getValue());
    Assertions.assertEquals(1, exit.getFields().size());
    Assertions.assertEquals(42, exit.getFields().get("fieldInt").getValue());
    Assertions.assertEquals(1, exit.getLocals().size());
    Assertions.assertEquals("foo", exit.getLocals().get("@return").getValue());
  }

  @Test
  public void roundtripLines() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    Snapshot.Captures captures = snapshot.getCaptures();
    CapturedContext lineCapturedContext = new CapturedContext();
    lineCapturedContext.addFields(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("fieldInt", "int", "42")
        });
    captures.addLine(24, lineCapturedContext);
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Map<Integer, CapturedContext> lines = deserializedSnapshot.getCaptures().getLines();
    Assertions.assertEquals(1, lines.size());
    Map<String, CapturedContext.CapturedValue> lineFields = lines.get(24).getFields();
    Assertions.assertEquals(1, lineFields.size());
    Assertions.assertEquals(42, lineFields.get("fieldInt").getValue());
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

  @Test
  public void primitives() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue objField =
        capturedValueDepth("objField", "Class", new AllPrimitives(), 3);
    context.addFields(new CapturedContext.CapturedValue[] {objField});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> thisFields = getFieldsFromJson(buffer);
    Map<String, Object> objFieldJson = (Map<String, Object>) thisFields.get("objField");
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
  }

  @Test
  public void wellKnownClasses() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue objField =
        capturedValueDepth(
            "objField", WellKnownClasses.class.getTypeName(), new WellKnownClasses(), 3);
    context.addFields(new CapturedContext.CapturedValue[] {objField});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> thisFields = getFieldsFromJson(buffer);
    Map<String, Object> objFieldJson = (Map<String, Object>) thisFields.get("objField");
    Map<String, Object> objFieldFields = (Map<String, Object>) objFieldJson.get(FIELDS);
    assertPrimitiveValue(
        objFieldFields,
        "clazz",
        Class.class.getTypeName(),
        "class com.datadog.debugger.agent.SnapshotSerializationTest$WellKnownClasses");
    assertPrimitiveValue(objFieldFields, "bool", Boolean.class.getTypeName(), "true");
    assertPrimitiveValue(objFieldFields, "l", Long.class.getTypeName(), "42");
    assertPrimitiveValue(objFieldFields, "bigDecimal", BigDecimal.class.getTypeName(), "3.1415926");
    assertPrimitiveValue(
        objFieldFields, "duration", Duration.class.getTypeName(), "PT342H56M7.89S");
    assertPrimitiveValue(
        objFieldFields, "localDateTime", LocalDateTime.class.getTypeName(), "2023-01-17T13:31");
    assertPrimitiveValue(
        objFieldFields, "uuid", UUID.class.getTypeName(), "3858f622-30ac-3c91-9f30-0c664312c63f");
    assertPrimitiveValue(objFieldFields, "atomicLong", AtomicLong.class.getTypeName(), "123");
    assertPrimitiveValue(
        objFieldFields, "uri", URI.class.getTypeName(), "https://www.datadoghq.com");
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
  public void depthLevel0() throws IOException, URISyntaxException {
    Map<String, Object> returnJson = doRefDepth(0);
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
    Map<String, Object> objField = (Map<String, Object>) thisFields.get("objField");
    Assertions.assertEquals(ComplexClass.class.getTypeName(), objField.get("type"));
    Map<String, Object> objFieldFields = (Map<String, Object>) objField.get(FIELDS);
    assertPrimitiveValue(objFieldFields, "complexIntField", "int", "21");
    assertPrimitiveValue(objFieldFields, "complexStrField", String.class.getTypeName(), "bar");
    assertNotCaptured(
        objFieldFields, "complexObjField", AnotherClass.class.getTypeName(), DEPTH_REASON);
    assertNotCaptured(
        objFieldFields, "complexObjField", AnotherClass.class.getTypeName(), DEPTH_REASON);
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
    Map<String, Object> complexObjField =
        (Map<String, Object>) objFieldFields.get("complexObjField");
    Assertions.assertEquals(AnotherClass.class.getTypeName(), complexObjField.get(TYPE));
    Map<String, Object> complexObjFieldFields = (Map<String, Object>) complexObjField.get(FIELDS);
    assertPrimitiveValue(complexObjFieldFields, "anotherIntField", "int", "11");
    assertPrimitiveValue(
        complexObjFieldFields, "anotherStrField", String.class.getTypeName(), "foobar");
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
    Map<String, Object> thisArgFields = doCollectionSize(0);
    assertNotCaptured(thisArgFields, "intArrayField", "int[]", COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbElements(thisArgFields, "intArrayField"));
    assertNotCaptured(
        thisArgFields, "strArrayField", String[].class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbElements(thisArgFields, "strArrayField"));
    assertNotCaptured(
        thisArgFields, "objArrayField", Object[].class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbElements(thisArgFields, "objArrayField"));
    assertNotCaptured(
        thisArgFields, "listField", ArrayList.class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbElements(thisArgFields, "listField"));
    assertNotCaptured(
        thisArgFields, "mapField", HashMap.class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(0, getNbEntries(thisArgFields, "mapField"));
  }

  @Test
  public void collectionSize3() throws IOException {
    Map<String, Object> thisArgFields = doCollectionSize(3);
    assertNotCaptured(thisArgFields, "intArrayField", "int[]", COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbElements(thisArgFields, "intArrayField"));
    assertArrayItem(thisArgFields, "intArrayField", "0", "1", "2");
    assertNotCaptured(
        thisArgFields, "strArrayField", String[].class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbElements(thisArgFields, "strArrayField"));
    assertArrayItem(thisArgFields, "strArrayField", "foo0", "foo1", "foo2");
    assertNotCaptured(
        thisArgFields, "objArrayField", Object[].class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbElements(thisArgFields, "objArrayField"));
    List<Object> objArrayElements = getArrayElements(thisArgFields, "objArrayField");
    assertComplexClass(objArrayElements.get(0), ComplexClass.class.getTypeName());
    assertComplexClass(objArrayElements.get(1), ComplexClass.class.getTypeName());
    assertComplexClass(objArrayElements.get(2), ComplexClass.class.getTypeName());
    assertNotCaptured(
        thisArgFields, "listField", ArrayList.class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbElements(thisArgFields, "listField"));
    assertArrayItem(thisArgFields, "listField", "foo0", "foo1", "foo2");
    assertNotCaptured(
        thisArgFields, "mapField", HashMap.class.getTypeName(), COLLECTION_SIZE_REASON);
    Assertions.assertEquals(3, getNbEntries(thisArgFields, "mapField"));
  }

  @Test
  public void collectionSize100() throws IOException {
    Map<String, Object> thisArgFields = doCollectionSize(100);
    assertNotCaptured(thisArgFields, "intArrayField", "int[]", null);
    Assertions.assertEquals(10, getNbElements(thisArgFields, "intArrayField"));
    assertArrayItem(
        thisArgFields, "intArrayField", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    assertNotCaptured(thisArgFields, "strArrayField", String[].class.getTypeName(), null);
    Assertions.assertEquals(10, getNbElements(thisArgFields, "strArrayField"));
    assertArrayItem(
        thisArgFields,
        "strArrayField",
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
    assertNotCaptured(thisArgFields, "objArrayField", Object[].class.getTypeName(), null);
    Assertions.assertEquals(10, getNbElements(thisArgFields, "objArrayField"));
    List<Object> objArrayElements = getArrayElements(thisArgFields, "objArrayField");
    assertComplexClass(objArrayElements.get(0), ComplexClass.class.getTypeName());
    assertComplexClass(objArrayElements.get(4), ComplexClass.class.getTypeName());
    assertComplexClass(objArrayElements.get(9), ComplexClass.class.getTypeName());
    assertNotCaptured(thisArgFields, "listField", ArrayList.class.getTypeName(), null);
    Assertions.assertEquals(10, getNbElements(thisArgFields, "listField"));
    assertArrayItem(
        thisArgFields,
        "listField",
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
    assertNotCaptured(thisArgFields, "mapField", HashMap.class.getTypeName(), null);
    Assertions.assertEquals(10, getNbEntries(thisArgFields, "mapField"));
    assertMapItems(
        thisArgFields,
        "mapField",
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
    return getFieldsFromJson(buffer);
  }

  @Test
  public void map0() throws IOException {
    Map<String, Object> thisArgFields = doMapSize(0);
    assertNotCaptured(thisArgFields, "strMap", HashMap.class.getTypeName(), COLLECTION_SIZE_REASON);
    assertMapItems(thisArgFields, "strMap");
    assertSize(thisArgFields, "strMap", "10");
  }

  @Test
  public void map3() throws IOException {
    Map<String, Object> thisArgFields = doMapSize(3);
    assertNotCaptured(thisArgFields, "strMap", HashMap.class.getTypeName(), COLLECTION_SIZE_REASON);
    Map<String, Object> field = (Map<String, Object>) thisArgFields.get("strMap");
    List<Object> entries = (List<Object>) field.get(ENTRIES);
    Assertions.assertEquals(3, entries.size());
    assertSize(thisArgFields, "strMap", "10");
  }

  @Test
  public void map100() throws IOException {
    Map<String, Object> thisArgFields = doMapSize(100);
    assertNotCaptured(thisArgFields, "strMap", HashMap.class.getTypeName(), null);
    assertMapItems(
        thisArgFields,
        "strMap",
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
    assertSize(thisArgFields, "strMap", "10");
  }

  private Map<String, Object> doMapSize(int maxColSize) throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshotForMapSize(maxColSize);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getFieldsFromJson(buffer);
  }

  @Test
  public void length0() throws IOException {
    Map<String, Object> thisArgFields = doLength(0);
    assertPrimitiveValue(thisArgFields, "strField", String.class.getTypeName(), "");
    assertTruncated(thisArgFields, "strField", String.class.getTypeName(), "true");
    assertSize(thisArgFields, "strField", "10");
  }

  @Test
  public void length3() throws IOException {
    Map<String, Object> thisArgFields = doLength(3);
    assertPrimitiveValue(thisArgFields, "strField", String.class.getTypeName(), "012");
    assertTruncated(thisArgFields, "strField", String.class.getTypeName(), "true");
    assertSize(thisArgFields, "strField", "10");
  }

  @Test
  public void length255() throws IOException {
    Map<String, Object> thisArgFields = doLength(255);
    assertPrimitiveValue(thisArgFields, "strField", String.class.getTypeName(), "0123456789");
    assertTruncated(thisArgFields, "strField", String.class.getTypeName(), "null");
    assertSize(thisArgFields, "strField", "null"); // no size field if no truncation
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
  public void collectionValueThrows() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue listField =
        CapturedContext.CapturedValue.of(
            "listField",
            List.class.getTypeName(),
            new ArrayList<String>() {
              @Override
              public int size() {
                throw new UnsupportedOperationException();
              }
            });
    context.addFields(new CapturedContext.CapturedValue[] {listField});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> fields = getFieldsFromJson(buffer);
    Map<String, Object> mapFieldObj = (Map<String, Object>) fields.get("listField");
    Assertions.assertEquals(
        "java.lang.UnsupportedOperationException", mapFieldObj.get(NOT_CAPTURED_REASON));
  }

  @Test
  public void mapValueThrows() throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue mapField =
        CapturedContext.CapturedValue.of(
            "mapField",
            Map.class.getTypeName(),
            new HashMap<String, String>() {
              @Override
              public Set<Entry<String, String>> entrySet() {
                throw new UnsupportedOperationException();
              }
            });
    context.addFields(new CapturedContext.CapturedValue[] {mapField});
    snapshot.setExit(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> fields = getFieldsFromJson(buffer);
    Map<String, Object> mapFieldObj = (Map<String, Object>) fields.get("mapField");
    Assertions.assertEquals(
        "java.lang.UnsupportedOperationException", mapFieldObj.get(NOT_CAPTURED_REASON));
  }

  private Map<String, Object> doLength(int maxLength) throws IOException {
    JsonAdapter<Snapshot> adapter = createSnapshotAdapter();
    Snapshot snapshot = createSnapshotForLength(maxLength);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getFieldsFromJson(buffer);
  }

  @Test
  public void fieldCount0() throws IOException {
    Map<String, Object> thisArg = doFieldCount(0);
    Assertions.assertEquals(
        0, ((Map<String, CapturedContext.CapturedValue>) thisArg.get(FIELDS)).size());
    Assertions.assertEquals(FIELD_COUNT_REASON, thisArg.get(NOT_CAPTURED_REASON));
  }

  @Test
  public void fieldCount3() throws IOException {
    Map<String, Object> thisArg = doFieldCount(3);
    Assertions.assertEquals(
        3, ((Map<String, CapturedContext.CapturedValue>) thisArg.get(FIELDS)).size());
    Assertions.assertEquals(FIELD_COUNT_REASON, thisArg.get(NOT_CAPTURED_REASON));
  }

  @Test
  public void fieldCount20() throws IOException {
    Map<String, Object> thisArg = doFieldCount(20);
    Assertions.assertEquals(
        4, ((Map<String, CapturedContext.CapturedValue>) thisArg.get(FIELDS)).size());
    Assertions.assertNull(thisArg.get(NOT_CAPTURED_REASON));
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
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    Map<String, Object> thisArg = (Map<String, Object>) arguments.get(THIS);
    return (Map<String, Object>) thisArg.get(FIELDS);
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
    CapturedContext.CapturedValue intField = capturedValueDepth("intField", "int", 42, maxRefDepth);
    CapturedContext.CapturedValue strField =
        capturedValueDepth("strField", String.class.getTypeName(), "foo", maxRefDepth);
    CapturedContext.CapturedValue objField =
        capturedValueDepth(
            "objField", ComplexClass.class.getTypeName(), new ComplexClass(), maxRefDepth);
    CapturedContext.CapturedValue nullField =
        capturedValueDepth("nullField", ComplexClass.class.getTypeName(), null, maxRefDepth);
    context.addFields(
        new CapturedContext.CapturedValue[] {intField, strField, objField, nullField});
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
    CapturedContext.CapturedValue intArrayField =
        capturedValueColSize(
            "intArrayField", "int[]", new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, maxColSize);
    CapturedContext.CapturedValue strArrayField =
        capturedValueColSize(
            "strArrayField",
            String[].class.getTypeName(),
            new String[] {
              "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8", "foo9"
            },
            maxColSize);
    CapturedContext.CapturedValue objArrayField =
        capturedValueColSize(
            "objArrayField",
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
    CapturedContext.CapturedValue listField =
        capturedValueColSize(
            "listField",
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
    CapturedContext.CapturedValue mapField =
        capturedValueColSize("mapField", Map.class.getTypeName(), mapObj, maxColSize);
    context.addFields(
        new CapturedContext.CapturedValue[] {
          intArrayField, strArrayField, objArrayField, listField, mapField
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
    context.addFields(new CapturedContext.CapturedValue[] {map});
    snapshot.setExit(context);
    return snapshot;
  }

  private Snapshot createSnapshotForLength(int maxLength) {
    Snapshot snapshot = createSnapshot();
    CapturedContext context = new CapturedContext();
    CapturedContext.CapturedValue strField =
        CapturedContext.CapturedValue.of(
            "strField",
            String.class.getTypeName(),
            "0123456789",
            Limits.DEFAULT_REFERENCE_DEPTH,
            Limits.DEFAULT_COLLECTION_SIZE,
            maxLength,
            Limits.DEFAULT_FIELD_COUNT);
    context.addFields(new CapturedContext.CapturedValue[] {strField});
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
    CapturedContext.CapturedValue intField =
        capturedValueDepth("intField", "int", 42, maxFieldCount);
    CapturedContext.CapturedValue strField =
        capturedValueDepth("strField", String.class.getTypeName(), "foo", maxFieldCount);
    CapturedContext.CapturedValue objField =
        capturedValueDepth(
            "objField", ComplexClass.class.getTypeName(), new ComplexClass(), maxFieldCount);
    CapturedContext.CapturedValue nullField =
        capturedValueDepth("nullField", ComplexClass.class.getTypeName(), null, maxFieldCount);
    context.addFields(
        new CapturedContext.CapturedValue[] {intField, strField, objField, nullField});
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
    Assertions.assertNull(capturedStackFrame.getFileName());
    Assertions.assertEquals(methodName, capturedStackFrame.getFunction());
    Assertions.assertEquals(lineNumber, capturedStackFrame.getLineNumber());
  }

  private Snapshot createSnapshot() {
    return new Snapshot(
        Thread.currentThread(),
        new ProbeImplementation.NoopProbeImplementation(PROBE_ID, PROBE_LOCATION));
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
