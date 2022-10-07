package com.datadog.debugger.agent;

import static com.datadog.debugger.sink.DebuggerSinkTest.SINK_FIXTURE_PREFIX;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ARGUMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.CAPTURES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.COLLECTION_SIZE_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.DEPTH_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ELEMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ENTRIES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELDS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELD_COUNT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.IS_NULL;
import static com.datadog.debugger.util.MoshiSnapshotHelper.LOCALS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.RETURN;
import static com.datadog.debugger.util.MoshiSnapshotHelper.SIZE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.THIS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TRUNCATED;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TYPE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.VALUE;
import static utils.TestHelper.getFixtureContent;

import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

public class SnapshotSerializationTest {

  private static final String PROBE_ID = "12fd-8490-c111-4374-ffde";
  private static final Snapshot.ProbeLocation PROBE_LOCATION =
      new Snapshot.ProbeLocation(
          "java.lang.String", "indexOf", "String.java", Arrays.asList("12-15", "23"));

  @BeforeEach
  public void setup() {
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    DebuggerContext.initSnapshotSerializer(new JsonSnapshotSerializer());
  }

  @Test
  public void roundTripProbeLocation() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Snapshot.ProbeLocation location = deserializedSnapshot.getProbe().getLocation();
    Assert.assertEquals(PROBE_LOCATION.getType(), location.getType());
    Assert.assertEquals(PROBE_LOCATION.getFile(), location.getFile());
    Assert.assertEquals(PROBE_LOCATION.getMethod(), location.getMethod());
    Assert.assertEquals(PROBE_LOCATION.getLines(), location.getLines());
  }

  @Test
  @EnabledOnJre(JRE.JAVA_17)
  public void roundTripCapturedValue() throws IOException, URISyntaxException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue normalValuedField =
        Snapshot.CapturedValue.of("normalValuedField", String.class.getName(), "foobar");
    Snapshot.CapturedValue normalNullField =
        Snapshot.CapturedValue.of("normalNullField", String.class.getName(), null);
    // this object generates InaccessibleObjectException since JDK16 when extracting its fields
    Snapshot.CapturedValue notCapturedField =
        Snapshot.CapturedValue.of(
            "notCapturedField",
            OperatingSystemMXBean.class.getName(),
            ManagementFactory.getOperatingSystemMXBean());
    context.addFields(
        new Snapshot.CapturedValue[] {normalValuedField, normalNullField, notCapturedField});
    captures.setReturn(context);
    String buffer = adapter.toJson(snapshot);
    String snapshotRegex =
        getFixtureContent(SINK_FIXTURE_PREFIX + "/snapshotCapturedValueRegex.txt");
    snapshotRegex = snapshotRegex.replaceAll("\\n", "");
    Assert.assertTrue(buffer, buffer.matches(snapshotRegex));
    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Map<String, Snapshot.CapturedValue> fields =
        deserializedSnapshot.getCaptures().getReturn().getFields();
    Assert.assertEquals(3, fields.size());
    normalValuedField = fields.get("normalValuedField");
    Assert.assertEquals("foobar", normalValuedField.getValue());
    Assert.assertEquals(String.class.getName(), normalValuedField.getType());
    Assert.assertNull(normalValuedField.getNotCapturedReason());
    normalNullField = fields.get("normalNullField");
    Assert.assertNull(normalNullField.getValue());
    Assert.assertEquals(String.class.getName(), normalNullField.getType());
    Assert.assertNull(normalNullField.getNotCapturedReason());
    notCapturedField = fields.get("notCapturedField");
    Map<String, Snapshot.CapturedValue> notCapturedFields =
        (Map<String, Snapshot.CapturedValue>) notCapturedField.getValue();
    Snapshot.CapturedValue processLoadTicks = notCapturedFields.get("processLoadTicks");
    Assert.assertTrue(
        processLoadTicks
            .getNotCapturedReason()
            .startsWith(
                "java.lang.reflect.InaccessibleObjectException: Unable to make field private com.sun.management.internal.OperatingSystemImpl$ContainerCpuTicks com.sun.management.internal.OperatingSystemImpl.processLoadTicks accessible: module jdk.management does not \"opens com.sun.management.internal\" to unnamed module @"));
    Snapshot.CapturedValue systemLoadTicks = notCapturedFields.get("systemLoadTicks");
    Assert.assertTrue(
        systemLoadTicks
            .getNotCapturedReason()
            .startsWith(
                "java.lang.reflect.InaccessibleObjectException: Unable to make field private com.sun.management.internal.OperatingSystemImpl$ContainerCpuTicks com.sun.management.internal.OperatingSystemImpl.systemLoadTicks accessible: module jdk.management does not \"opens com.sun.management.internal\" to unnamed module @"));
    Snapshot.CapturedValue containerMetrics = notCapturedFields.get("containerMetrics");
    Assert.assertTrue(
        containerMetrics
            .getNotCapturedReason()
            .startsWith(
                "java.lang.reflect.InaccessibleObjectException: Unable to make field private final jdk.internal.platform.Metrics com.sun.management.internal.OperatingSystemImpl.containerMetrics accessible: module jdk.management does not \"opens com.sun.management.internal\" to unnamed module @"));
  }

  @Test
  public void roundTripCaughtException() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    captures.addCaughtException(
        new Snapshot.CapturedThrowable(
            IllegalArgumentException.class.getName(),
            "illegal argument",
            Arrays.asList(
                new CapturedStackFrame("f1", 12),
                new CapturedStackFrame("f2", 23),
                new CapturedStackFrame("f3", 34))));
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    List<CapturedStackFrame> stacktrace =
        deserializedSnapshot.getCaptures().getCaughtExceptions().get(0).getStacktrace();
    Assert.assertEquals(3, stacktrace.size());
    assertCapturedFrame(stacktrace.get(0), "f1", 12);
    assertCapturedFrame(stacktrace.get(1), "f2", 23);
    assertCapturedFrame(stacktrace.get(2), "f3", 34);
  }

  @Test
  public void roundtripEntryReturn() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext entryCapturedContext = new Snapshot.CapturedContext();
    entryCapturedContext.addFields(
        new Snapshot.CapturedValue[] {Snapshot.CapturedValue.of("fieldInt", "int", "42")});
    captures.setEntry(entryCapturedContext);
    Snapshot.CapturedContext exitCapturedContext = new Snapshot.CapturedContext();
    exitCapturedContext.addFields(
        new Snapshot.CapturedValue[] {Snapshot.CapturedValue.of("fieldInt", "int", "42")});
    exitCapturedContext.addReturn(Snapshot.CapturedValue.of(String.class.getName(), "foo"));
    captures.setReturn(exitCapturedContext);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Snapshot.CapturedContext entry = deserializedSnapshot.getCaptures().getEntry();
    Snapshot.CapturedContext exit = deserializedSnapshot.getCaptures().getReturn();
    Assert.assertEquals(1, entry.getFields().size());
    Assert.assertEquals(42, entry.getFields().get("fieldInt").getValue());
    Assert.assertEquals(1, exit.getFields().size());
    Assert.assertEquals(42, exit.getFields().get("fieldInt").getValue());
    Assert.assertEquals(1, exit.getLocals().size());
    Assert.assertEquals("foo", exit.getLocals().get("@return").getValue());
  }

  @Test
  public void roundtripLines() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext lineCapturedContext = new Snapshot.CapturedContext();
    lineCapturedContext.addFields(
        new Snapshot.CapturedValue[] {Snapshot.CapturedValue.of("fieldInt", "int", "42")});
    captures.addLine(24, lineCapturedContext);
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Map<Integer, Snapshot.CapturedContext> lines = deserializedSnapshot.getCaptures().getLines();
    Assert.assertEquals(1, lines.size());
    Map<String, Snapshot.CapturedValue> lineFields = lines.get(24).getFields();
    Assert.assertEquals(1, lineFields.size());
    Assert.assertEquals(42, lineFields.get("fieldInt").getValue());
  }

  class AnotherClass {
    int anotherIntField = 11;
    String anotherStrField = "foobar";
  }

  class ComplexClass {
    int complexIntField = 21;
    String complexStrField = "bar";
    AnotherClass complexObjField = new AnotherClass();
  }

  class AllPrimitives {
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
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue objField =
        capturedValueDepth("objField", "Class", new AllPrimitives(), 3);
    context.addFields(new Snapshot.CapturedValue[] {objField});
    captures.setReturn(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    Map<String, Object> thisArg = (Map<String, Object>) arguments.get(THIS);
    Map<String, Object> objFieldJson = (Map<String, Object>) thisArg.get("objField");
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

  @Test
  public void objectArray() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue localObjArray =
        capturedValueDepth(
            "localObjArray", Object[].class.getName(), new Object[] {"foo", null, 42}, 3);
    context.addLocals(new Snapshot.CapturedValue[] {localObjArray});
    captures.setReturn(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
    assertArrayItem(locals, "localObjArray", "foo", null, "42");
  }

  class ObjetArrayClass {
    ComplexClass[] complexClasses = new ComplexClass[3];
  }

  @Test
  public void fieldObjectArray() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue localObj =
        capturedValueDepth("localObj", ObjetArrayClass.class.getName(), new ObjetArrayClass(), 3);
    context.addLocals(new Snapshot.CapturedValue[] {localObj});
    captures.setReturn(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
    Map<String, Object> localObjMap = (Map<String, Object>) locals.get("localObj");
    Map<String, Object> localObjFieldsMap = (Map<String, Object>) localObjMap.get(FIELDS);
    Map<String, Object> complexClasses =
        (Map<String, Object>) localObjFieldsMap.get("complexClasses");
    Assert.assertEquals(
        "com.datadog.debugger.agent.SnapshotSerializationTest$ComplexClass[]",
        complexClasses.get(TYPE));
  }

  class PrimitiveArrayClass {
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
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue localObj =
        capturedValueDepth(
            "localObj", PrimitiveArrayClass.class.getName(), new PrimitiveArrayClass(), 3);
    context.addLocals(new Snapshot.CapturedValue[] {localObj});
    captures.setReturn(context);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
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
    assertPrimitiveValue(thisArg, "strField", String.class.getName(), "foo");
    assertPrimitiveValue(thisArg, "nullField", ComplexClass.class.getName(), null);
    assertPrimitiveValue(thisArg, "intField", "int", "42");
    assertNotCaptured(thisArg, "objField", ComplexClass.class.getName(), DEPTH_REASON);
    assertPrimitiveValue(arguments, "strArg", String.class.getName(), null);
    assertPrimitiveValue(arguments, "intArg", "int", "0");
    assertPrimitiveValue(arguments, "objArg", ComplexClass.class.getName(), null);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
    assertPrimitiveValue(locals, "intLocal", "int", "42");
    assertNotCaptured(locals, "objLocal", ComplexClass.class.getName(), DEPTH_REASON);
  }

  @Test
  public void depthLevel1() throws IOException, URISyntaxException {
    Map<String, Object> returnJson = doRefDepth(1);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    Map<String, Object> thisArg = (Map<String, Object>) arguments.get(THIS);
    assertPrimitiveValue(thisArg, "strField", String.class.getName(), "foo");
    assertPrimitiveValue(thisArg, "nullField", ComplexClass.class.getName(), null);
    assertPrimitiveValue(thisArg, "intField", "int", "42");
    Map<String, Object> objField = (Map<String, Object>) thisArg.get("objField");
    Assert.assertEquals(ComplexClass.class.getName(), objField.get("type"));
    Map<String, Object> objFieldFields = (Map<String, Object>) objField.get(FIELDS);
    assertPrimitiveValue(objFieldFields, "complexIntField", "int", "21");
    assertPrimitiveValue(objFieldFields, "complexStrField", String.class.getName(), "bar");
    assertNotCaptured(
        objFieldFields, "complexObjField", AnotherClass.class.getName(), DEPTH_REASON);
    assertNotCaptured(
        objFieldFields, "complexObjField", AnotherClass.class.getName(), DEPTH_REASON);
    assertNotCaptured(
        objFieldFields,
        "this$0",
        "com.datadog.debugger.agent.SnapshotSerializationTest",
        DEPTH_REASON);
    assertPrimitiveValue(arguments, "strArg", String.class.getName(), null);
    assertPrimitiveValue(arguments, "intArg", "int", "0");
    assertPrimitiveValue(arguments, "objArg", ComplexClass.class.getName(), null);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
    assertPrimitiveValue(locals, "intLocal", "int", "42");
    Map<String, Object> objLocal = (Map<String, Object>) locals.get("objLocal");
    Assert.assertEquals(ComplexClass.class.getName(), objLocal.get(TYPE));
    Map<String, Object> objLocalFields = (Map<String, Object>) objLocal.get(FIELDS);
    assertPrimitiveValue(objLocalFields, "complexIntField", "int", "21");
    assertPrimitiveValue(objLocalFields, "complexStrField", String.class.getName(), "bar");
    assertNotCaptured(
        objLocalFields, "complexObjField", AnotherClass.class.getName(), DEPTH_REASON);
    assertNotCaptured(
        objLocalFields, "complexObjField", AnotherClass.class.getName(), DEPTH_REASON);
    assertNotCaptured(
        objLocalFields,
        "this$0",
        "com.datadog.debugger.agent.SnapshotSerializationTest",
        DEPTH_REASON);
  }

  @Test
  public void depthLevel2() throws IOException, URISyntaxException {
    Map<String, Object> returnJson = doRefDepth(2);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    Map<String, Object> thisArg = (Map<String, Object>) arguments.get(THIS);
    assertPrimitiveValue(thisArg, "strField", String.class.getName(), "foo");
    assertPrimitiveValue(thisArg, "nullField", ComplexClass.class.getName(), null);
    assertPrimitiveValue(thisArg, "intField", "int", "42");
    Map<String, Object> objField = (Map<String, Object>) thisArg.get("objField");
    Assert.assertEquals(ComplexClass.class.getName(), objField.get(TYPE));
    Map<String, Object> objFieldFields = (Map<String, Object>) objField.get(FIELDS);
    assertPrimitiveValue(objFieldFields, "complexIntField", "int", "21");
    assertPrimitiveValue(objFieldFields, "complexStrField", String.class.getName(), "bar");
    Map<String, Object> complexObjField =
        (Map<String, Object>) objFieldFields.get("complexObjField");
    Assert.assertEquals(AnotherClass.class.getName(), complexObjField.get(TYPE));
    Map<String, Object> complexObjFieldFields = (Map<String, Object>) complexObjField.get(FIELDS);
    assertPrimitiveValue(complexObjFieldFields, "anotherIntField", "int", "11");
    assertPrimitiveValue(
        complexObjFieldFields, "anotherStrField", String.class.getName(), "foobar");
    assertNotCaptured(
        complexObjFieldFields,
        "this$0",
        "com.datadog.debugger.agent.SnapshotSerializationTest",
        DEPTH_REASON);
    assertPrimitiveValue(arguments, "strArg", String.class.getName(), null);
    assertPrimitiveValue(arguments, "intArg", "int", "0");
    assertPrimitiveValue(arguments, "objArg", ComplexClass.class.getName(), null);
    Map<String, Object> locals = (Map<String, Object>) returnJson.get(LOCALS);
    assertPrimitiveValue(locals, "intLocal", "int", "42");
    Map<String, Object> objLocal = (Map<String, Object>) locals.get("objLocal");
    Assert.assertEquals(ComplexClass.class.getName(), objLocal.get("type"));
    Map<String, Object> objLocalFields = (Map<String, Object>) objLocal.get(FIELDS);
    assertPrimitiveValue(objLocalFields, "complexIntField", "int", "21");
    assertPrimitiveValue(objLocalFields, "complexStrField", String.class.getName(), "bar");
    Map<String, Object> localComplexObjField =
        (Map<String, Object>) objLocalFields.get("complexObjField");
    Assert.assertEquals(AnotherClass.class.getName(), localComplexObjField.get(TYPE));
    Map<String, Object> localComplexObjFieldFields =
        (Map<String, Object>) localComplexObjField.get(FIELDS);
    assertPrimitiveValue(localComplexObjFieldFields, "anotherIntField", "int", "11");
    assertPrimitiveValue(
        localComplexObjFieldFields, "anotherStrField", String.class.getName(), "foobar");
    assertNotCaptured(
        localComplexObjFieldFields,
        "this$0",
        "com.datadog.debugger.agent.SnapshotSerializationTest",
        DEPTH_REASON);
  }

  private Map<String, Object> doRefDepth(int maxRefDepth) throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot = createSnapshotForRefDepth(maxRefDepth);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    return (Map<String, Object>) capturesJson.get(RETURN);
  }

  @Test
  public void collectionSize0() throws IOException {
    Map<String, Object> thisArg = doCollectionSize(0);
    assertNotCaptured(thisArg, "intArrayField", "int[]", COLLECTION_SIZE_REASON);
    Assert.assertEquals(0, getNbElements(thisArg, "intArrayField"));
    assertNotCaptured(thisArg, "strArrayField", String[].class.getName(), COLLECTION_SIZE_REASON);
    Assert.assertEquals(0, getNbElements(thisArg, "strArrayField"));
    assertNotCaptured(thisArg, "objArrayField", Object[].class.getName(), COLLECTION_SIZE_REASON);
    Assert.assertEquals(0, getNbElements(thisArg, "objArrayField"));
    assertNotCaptured(thisArg, "listField", List.class.getName(), COLLECTION_SIZE_REASON);
    Assert.assertEquals(0, getNbElements(thisArg, "listField"));
    assertNotCaptured(thisArg, "mapField", Map.class.getName(), COLLECTION_SIZE_REASON);
    Assert.assertEquals(0, getNbEntries(thisArg, "mapField"));
  }

  @Test
  public void collectionSize3() throws IOException {
    Map<String, Object> thisArg = doCollectionSize(3);
    assertNotCaptured(thisArg, "intArrayField", "int[]", COLLECTION_SIZE_REASON);
    Assert.assertEquals(3, getNbElements(thisArg, "intArrayField"));
    assertArrayItem(thisArg, "intArrayField", "0", "1", "2");
    assertNotCaptured(thisArg, "strArrayField", String[].class.getName(), COLLECTION_SIZE_REASON);
    Assert.assertEquals(3, getNbElements(thisArg, "strArrayField"));
    assertArrayItem(thisArg, "strArrayField", "foo0", "foo1", "foo2");
    assertNotCaptured(thisArg, "objArrayField", Object[].class.getName(), COLLECTION_SIZE_REASON);
    Assert.assertEquals(3, getNbElements(thisArg, "objArrayField"));
    List<Object> objArrayElements = getArrayElements(thisArg, "objArrayField");
    assertComplexClass(objArrayElements.get(0), ComplexClass.class.getName());
    assertComplexClass(objArrayElements.get(1), ComplexClass.class.getName());
    assertComplexClass(objArrayElements.get(2), ComplexClass.class.getName());
    assertNotCaptured(thisArg, "listField", List.class.getName(), COLLECTION_SIZE_REASON);
    Assert.assertEquals(3, getNbElements(thisArg, "listField"));
    assertArrayItem(thisArg, "listField", "foo0", "foo1", "foo2");
    assertNotCaptured(thisArg, "mapField", Map.class.getName(), COLLECTION_SIZE_REASON);
    Assert.assertEquals(3, getNbEntries(thisArg, "mapField"));
  }

  @Test
  public void collectionSize100() throws IOException {
    Map<String, Object> thisArg = doCollectionSize(100);
    assertNotCaptured(thisArg, "intArrayField", "int[]", null);
    Assert.assertEquals(10, getNbElements(thisArg, "intArrayField"));
    assertArrayItem(thisArg, "intArrayField", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    assertNotCaptured(thisArg, "strArrayField", String[].class.getName(), null);
    Assert.assertEquals(10, getNbElements(thisArg, "strArrayField"));
    assertArrayItem(
        thisArg,
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
    assertNotCaptured(thisArg, "objArrayField", Object[].class.getName(), null);
    Assert.assertEquals(10, getNbElements(thisArg, "objArrayField"));
    List<Object> objArrayElements = getArrayElements(thisArg, "objArrayField");
    assertComplexClass(objArrayElements.get(0), ComplexClass.class.getName());
    assertComplexClass(objArrayElements.get(4), ComplexClass.class.getName());
    assertComplexClass(objArrayElements.get(9), ComplexClass.class.getName());
    assertNotCaptured(thisArg, "listField", List.class.getName(), null);
    Assert.assertEquals(10, getNbElements(thisArg, "listField"));
    assertArrayItem(
        thisArg,
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
    assertNotCaptured(thisArg, "mapField", Map.class.getName(), null);
    Assert.assertEquals(10, getNbEntries(thisArg, "mapField"));
    assertMapItems(
        thisArg,
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
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot = createSnapshotForCollectionSize(maxColSize);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getFieldsFromJson(buffer);
  }

  @Test
  public void map0() throws IOException {
    Map<String, Object> thisArg = doMapSize(0);
    assertNotCaptured(thisArg, "strMap", HashMap.class.getName(), COLLECTION_SIZE_REASON);
    assertMapItems(thisArg, "strMap");
    assertSize(thisArg, "strMap", "10");
  }

  @Test
  public void map3() throws IOException {
    Map<String, Object> thisArg = doMapSize(3);
    assertNotCaptured(thisArg, "strMap", HashMap.class.getName(), COLLECTION_SIZE_REASON);
    Map<String, Object> field = (Map<String, Object>) thisArg.get("strMap");
    List<Object> entries = (List<Object>) field.get(ENTRIES);
    Assert.assertEquals(3, entries.size());
    assertSize(thisArg, "strMap", "10");
  }

  @Test
  public void map100() throws IOException {
    Map<String, Object> thisArg = doMapSize(100);
    assertNotCaptured(thisArg, "strMap", HashMap.class.getName(), null);
    assertMapItems(
        thisArg, "strMap", "foo0", "bar0", "foo1", "bar1", "foo2", "bar2", "foo3", "bar3", "foo4",
        "bar4", "foo5", "bar5", "foo6", "bar6", "foo7", "bar7", "foo8", "bar8", "foo9", "bar9");
    assertSize(thisArg, "strMap", "10");
  }

  private Map<String, Object> doMapSize(int maxColSize) throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot = createSnapshotForMapSize(maxColSize);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getFieldsFromJson(buffer);
  }

  @Test
  public void length0() throws IOException {
    Map<String, Object> thisArg = doLength(0);
    assertPrimitiveValue(thisArg, "strField", String.class.getName(), "");
    assertTruncated(thisArg, "strField", String.class.getName(), "true");
    assertSize(thisArg, "strField", "10");
  }

  @Test
  public void length3() throws IOException {
    Map<String, Object> thisArg = doLength(3);
    assertPrimitiveValue(thisArg, "strField", String.class.getName(), "012");
    assertTruncated(thisArg, "strField", String.class.getName(), "true");
    assertSize(thisArg, "strField", "10");
  }

  @Test
  public void length255() throws IOException {
    Map<String, Object> thisArg = doLength(255);
    assertPrimitiveValue(thisArg, "strField", String.class.getName(), "0123456789");
    assertTruncated(thisArg, "strField", String.class.getName(), "null");
    assertSize(thisArg, "strField", "null"); // no size field if no truncation
  }

  private Map<String, Object> doLength(int maxLength) throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot = createSnapshotForLength(maxLength);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getFieldsFromJson(buffer);
  }

  @Test
  public void fieldCount0() throws IOException {
    Map<String, Object> thisArg = doFieldCount(0);
    Assert.assertEquals(1, thisArg.size());
    Assert.assertEquals(FIELD_COUNT_REASON, thisArg.get(NOT_CAPTURED_REASON));
  }

  @Test
  public void fieldCount3() throws IOException {
    Map<String, Object> thisArg = doFieldCount(3);
    Assert.assertEquals(4, thisArg.size());
    Assert.assertEquals(FIELD_COUNT_REASON, thisArg.get(NOT_CAPTURED_REASON));
  }

  @Test
  public void fieldCount20() throws IOException {
    Map<String, Object> thisArg = doFieldCount(20);
    Assert.assertEquals(4, thisArg.size());
    Assert.assertNull(thisArg.get(NOT_CAPTURED_REASON));
  }

  private Map<String, Object> doFieldCount(int maxFieldCount) throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot = createSnapshotForFieldCount(maxFieldCount);
    String buffer = adapter.toJson(snapshot);
    System.out.println(buffer);
    return getFieldsFromJson(buffer);
  }

  private void assertComplexClass(Object obj, String type) {
    Map<String, Object> objMap = (Map<String, Object>) obj;
    Assert.assertEquals(type, objMap.get(TYPE));
    Assert.assertTrue(objMap.containsKey(FIELDS));
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
      Assert.assertEquals(values[i], elem.get(VALUE));
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
    Assert.assertEquals(expectedValues.length / 2, entries.size());
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
      Assert.assertTrue(tmpMap.containsKey(key));
      Assert.assertEquals(value, tmpMap.get(key));
    }
  }

  private void assertNotCaptured(
      Map<String, Object> item, String name, String type, String reason) {
    Map<String, Object> obj = (Map<String, Object>) item.get(name);
    Assert.assertEquals(type, obj.get(TYPE));
    Assert.assertEquals(reason, obj.get(NOT_CAPTURED_REASON));
  }

  private void assertTruncated(
      Map<String, Object> item, String name, String type, String truncated) {
    Map<String, Object> obj = (Map<String, Object>) item.get(name);
    Assert.assertEquals(type, obj.get(TYPE));
    Assert.assertEquals(truncated, String.valueOf(obj.get(TRUNCATED)));
  }

  private void assertSize(Map<String, Object> item, String name, String size) {
    Map<String, Object> obj = (Map<String, Object>) item.get(name);
    Assert.assertEquals(size, String.valueOf(obj.get(SIZE)));
  }

  private void assertPrimitiveValue(
      Map<String, Object> item, String name, String type, String value) {
    Map<String, Object> prim = (Map<String, Object>) item.get(name);
    Assert.assertEquals(type, prim.get(TYPE));
    if (value != null) {
      Assert.assertEquals(value, String.valueOf(prim.get(VALUE)));
    } else {
      Assert.assertEquals(true, prim.get(IS_NULL));
    }
  }

  private Map<String, Object> getFieldsFromJson(String buffer) throws IOException {
    Map<String, Object> json = MoshiHelper.createGenericAdapter().fromJson(buffer);
    Map<String, Object> capturesJson = (Map<String, Object>) json.get(CAPTURES);
    Map<String, Object> returnJson = (Map<String, Object>) capturesJson.get(RETURN);
    Map<String, Object> arguments = (Map<String, Object>) returnJson.get(ARGUMENTS);
    return (Map<String, Object>) arguments.get(THIS);
  }

  private Snapshot createSnapshotForRefDepth(int maxRefDepth) {
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue intField = capturedValueDepth("intField", "int", 42, maxRefDepth);
    Snapshot.CapturedValue strField =
        capturedValueDepth("strField", String.class.getName(), "foo", maxRefDepth);
    Snapshot.CapturedValue objField =
        capturedValueDepth(
            "objField", ComplexClass.class.getName(), new ComplexClass(), maxRefDepth);
    Snapshot.CapturedValue nullField =
        capturedValueDepth("nullField", ComplexClass.class.getName(), null, maxRefDepth);
    context.addFields(new Snapshot.CapturedValue[] {intField, strField, objField, nullField});
    Snapshot.CapturedValue intArg = capturedValueDepth("intArg", "int", 0, maxRefDepth);
    Snapshot.CapturedValue strArg =
        capturedValueDepth("strArg", String.class.getName(), null, maxRefDepth);
    Snapshot.CapturedValue objArg =
        capturedValueDepth("objArg", ComplexClass.class.getName(), null, maxRefDepth);
    context.addArguments(new Snapshot.CapturedValue[] {intArg, strArg, objArg});
    Snapshot.CapturedValue intLocal = capturedValueDepth("intLocal", "int", 42, maxRefDepth);
    Snapshot.CapturedValue objLocal =
        capturedValueDepth(
            "objLocal", ComplexClass.class.getName(), new ComplexClass(), maxRefDepth);
    context.addLocals(new Snapshot.CapturedValue[] {intLocal, objLocal});
    captures.setReturn(context);
    return snapshot;
  }

  private Snapshot createSnapshotForCollectionSize(int maxColSize) {
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue intArrayField =
        capturedValueColSize(
            "intArrayField", "int[]", new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, maxColSize);
    Snapshot.CapturedValue strArrayField =
        capturedValueColSize(
            "strArrayField",
            String[].class.getName(),
            new String[] {
              "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8", "foo9"
            },
            maxColSize);
    Snapshot.CapturedValue objArrayField =
        capturedValueColSize(
            "objArrayField",
            Object[].class.getName(),
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
    Snapshot.CapturedValue listField =
        capturedValueColSize(
            "listField",
            List.class.getName(),
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
    Snapshot.CapturedValue mapField =
        capturedValueColSize("mapField", Map.class.getName(), mapObj, maxColSize);
    context.addFields(
        new Snapshot.CapturedValue[] {
          intArrayField, strArrayField, objArrayField, listField, mapField
        });
    captures.setReturn(context);
    return snapshot;
  }

  private Snapshot createSnapshotForMapSize(int maxColSize) {
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
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
    Snapshot.CapturedValue map =
        Snapshot.CapturedValue.of(
            "strMap",
            strMap.getClass().getName(),
            strMap,
            Limits.DEFAULT_REFERENCE_DEPTH,
            maxColSize,
            Limits.DEFAULT_LENGTH,
            Limits.DEFAULT_FIELD_COUNT);
    context.addFields(new Snapshot.CapturedValue[] {map});
    captures.setReturn(context);
    return snapshot;
  }

  private Snapshot createSnapshotForLength(int maxLength) {
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue strField =
        Snapshot.CapturedValue.of(
            "strField",
            String.class.getName(),
            "0123456789",
            Limits.DEFAULT_REFERENCE_DEPTH,
            Limits.DEFAULT_COLLECTION_SIZE,
            maxLength,
            Limits.DEFAULT_FIELD_COUNT);
    context.addFields(new Snapshot.CapturedValue[] {strField});
    captures.setReturn(context);
    return snapshot;
  }

  private Snapshot createSnapshotForFieldCount(int maxFieldCount) {
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    context.setLimits(
        Limits.DEFAULT_REFERENCE_DEPTH,
        Limits.DEFAULT_COLLECTION_SIZE,
        Limits.DEFAULT_LENGTH,
        maxFieldCount);
    Snapshot.CapturedValue intField = capturedValueDepth("intField", "int", 42, maxFieldCount);
    Snapshot.CapturedValue strField =
        capturedValueDepth("strField", String.class.getName(), "foo", maxFieldCount);
    Snapshot.CapturedValue objField =
        capturedValueDepth(
            "objField", ComplexClass.class.getName(), new ComplexClass(), maxFieldCount);
    Snapshot.CapturedValue nullField =
        capturedValueDepth("nullField", ComplexClass.class.getName(), null, maxFieldCount);
    context.addFields(new Snapshot.CapturedValue[] {intField, strField, objField, nullField});
    captures.setReturn(context);
    return snapshot;
  }

  private Snapshot.CapturedValue capturedValueDepth(
      String name, String type, Object value, int maxDepth) {
    return Snapshot.CapturedValue.of(
        name,
        type,
        value,
        maxDepth,
        Limits.DEFAULT_COLLECTION_SIZE,
        Limits.DEFAULT_LENGTH,
        Limits.DEFAULT_FIELD_COUNT);
  }

  private Snapshot.CapturedValue capturedValueColSize(
      String name, String type, Object value, int maxColSize) {
    return Snapshot.CapturedValue.of(
        name,
        type,
        value,
        Limits.DEFAULT_REFERENCE_DEPTH,
        maxColSize,
        Limits.DEFAULT_LENGTH,
        Limits.DEFAULT_FIELD_COUNT);
  }

  private Snapshot.CapturedValue capturedValueFieldCount(
      String name, String type, Object value, int maxFieldCount) {
    return Snapshot.CapturedValue.of(
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
    Assert.assertNull(capturedStackFrame.getFileName());
    Assert.assertEquals(methodName, capturedStackFrame.getFunction());
    Assert.assertEquals(lineNumber, capturedStackFrame.getLineNumber());
  }
}
