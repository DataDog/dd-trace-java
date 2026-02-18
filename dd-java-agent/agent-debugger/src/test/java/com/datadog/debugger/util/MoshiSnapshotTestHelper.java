package com.datadog.debugger.util;

import static com.datadog.debugger.util.MoshiSnapshotHelper.ARGUMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.CAPTURE_EXPRESSIONS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.CAUGHT_EXCEPTIONS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ELEMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ENTRIES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ENTRY;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELDS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ID;
import static com.datadog.debugger.util.MoshiSnapshotHelper.IS_NULL;
import static com.datadog.debugger.util.MoshiSnapshotHelper.LINES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.LOCALS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.LOCATION;
import static com.datadog.debugger.util.MoshiSnapshotHelper.MESSAGE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.RETURN;
import static com.datadog.debugger.util.MoshiSnapshotHelper.SIZE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.STACKTRACE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.STATIC_FIELDS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.THROWABLE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TRUNCATED;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TYPE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.VALUE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.VERSION;

import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.sink.Snapshot;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;

public class MoshiSnapshotTestHelper {

  public static final JsonAdapter<CapturedContext.CapturedValue> VALUE_ADAPTER =
      new MoshiSnapshotTestHelper.CapturedValueAdapter();

  public static CapturedContext.CapturedValue deserializeCapturedValue(
      CapturedContext.CapturedValue capturedValue) {
    try {
      return VALUE_ADAPTER.fromJson(capturedValue.getStrValue());
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static String getValue(CapturedContext.CapturedValue capturedValue) {
    CapturedContext.CapturedValue valued = null;
    try {
      Object obj;
      if (capturedValue.getStrValue() != null) {
        valued = VALUE_ADAPTER.fromJson(capturedValue.getStrValue());
        if (valued.getNotCapturedReason() != null) {
          Assertions.fail("NotCapturedReason: " + valued.getNotCapturedReason());
        }
        obj = valued.getValue();
      } else {
        obj = capturedValue.getValue();
      }
      if (obj != null && obj.getClass().isArray()) {
        if (obj.getClass().getComponentType().isPrimitive()) {
          return primitiveArrayToString(obj);
        }
        return Arrays.toString((Object[]) obj);
      }
      return obj != null ? String.valueOf(obj) : null;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static class SnapshotJsonFactory implements JsonAdapter.Factory {
    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> set, Moshi moshi) {
      if (Types.equals(type, Snapshot.Captures.class)) {
        return new MoshiSnapshotTestHelper.CapturesAdapter(
            moshi,
            new CapturedContextAdapter(
                moshi, new CapturedValueAdapter(), new CapturedThrowableAdapter(moshi)));
      }
      if (Types.equals(type, CapturedContext.CapturedValue.class)) {
        return new MoshiSnapshotTestHelper.CapturedValueAdapter();
      }
      if (Types.equals(type, CapturedContext.class)) {
        return new MoshiSnapshotTestHelper.CapturedContextAdapter(
            moshi, new CapturedValueAdapter(), new CapturedThrowableAdapter(moshi));
      }
      if (Types.equals(type, ProbeImplementation.class)) {
        return new MoshiSnapshotTestHelper.ProbeDetailsAdapter(moshi);
      }
      return null;
    }
  }

  public static Moshi createMoshiSnapshot() {
    return new Moshi.Builder()
        .add(new MoshiSnapshotTestHelper.SnapshotJsonFactory())
        .add(
            DebuggerScript.class,
            new ProbeCondition.ProbeConditionJsonAdapter()) // ProbeDetails in Snapshot
        .build();
  }

  private static String primitiveArrayToString(Object obj) {
    Class<?> componentType = obj.getClass().getComponentType();
    if (componentType == long.class) {
      return Arrays.toString((long[]) obj);
    }
    if (componentType == int.class) {
      return Arrays.toString((int[]) obj);
    }
    if (componentType == short.class) {
      return Arrays.toString((short[]) obj);
    }
    if (componentType == char.class) {
      return Arrays.toString((char[]) obj);
    }
    if (componentType == byte.class) {
      return Arrays.toString((byte[]) obj);
    }
    if (componentType == boolean.class) {
      return Arrays.toString((boolean[]) obj);
    }
    if (componentType == float.class) {
      return Arrays.toString((float[]) obj);
    }
    if (componentType == double.class) {
      return Arrays.toString((double[]) obj);
    }
    return null;
  }

  private static class CapturesAdapter extends MoshiSnapshotHelper.CapturesAdapter {

    public CapturesAdapter(Moshi moshi, JsonAdapter<CapturedContext> capturedContextAdapter) {
      super(moshi, capturedContextAdapter);
    }

    @Override
    public Snapshot.Captures fromJson(JsonReader jsonReader) throws IOException {
      jsonReader.beginObject();
      Snapshot.Captures captures = new Snapshot.Captures();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        switch (name) {
          case ENTRY:
            captures.setEntry(capturedContextAdapter.fromJson(jsonReader));
            break;
          case RETURN:
            captures.setReturn(capturedContextAdapter.fromJson(jsonReader));
            break;
          case LINES:
            Map<Integer, CapturedContext> map = linesAdapter.fromJson(jsonReader);
            if (map != null) {
              map.forEach(captures::addLine);
            }
            break;
          case CAUGHT_EXCEPTIONS:
            List<CapturedContext.CapturedThrowable> capturedThrowables =
                caughtExceptionsAdapter.fromJson(jsonReader);
            capturedThrowables.forEach(captures::addCaughtException);
            break;
          default:
            throw new IllegalArgumentException("Unknown field name for Captures object: " + name);
        }
      }
      jsonReader.endObject();
      return captures;
    }
  }

  public static class CapturedContextAdapter extends MoshiSnapshotHelper.CapturedContextAdapter {
    private static final CapturedContext.CapturedValue[] EMPTY_VALUES_ARRAY =
        new CapturedContext.CapturedValue[0];

    public CapturedContextAdapter(
        Moshi moshi,
        JsonAdapter<CapturedContext.CapturedValue> valueAdapter,
        MoshiSnapshotHelper.CapturedThrowableAdapter throwableAdapter) {
      super(moshi, valueAdapter, throwableAdapter);
    }

    @Override
    public CapturedContext fromJson(JsonReader jsonReader) throws IOException {
      jsonReader.beginObject();
      CapturedContext capturedContext = new CapturedContext();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        switch (name) {
          case ARGUMENTS:
            jsonReader.beginObject();
            List<CapturedContext.CapturedValue> argValues = new ArrayList<>();
            while (jsonReader.hasNext()) {
              String argName = jsonReader.nextName();
              CapturedContext.CapturedValue capturedValue = valueAdapter.fromJson(jsonReader);
              if (capturedValue != null) {
                capturedValue.setName(argName);
                argValues.add(capturedValue);
              }
            }
            jsonReader.endObject();
            capturedContext.addArguments(argValues.toArray(EMPTY_VALUES_ARRAY));
            break;
          case LOCALS:
            capturedContext.addLocals(fromJsonCapturedValues(jsonReader));
            break;
          case STATIC_FIELDS:
            capturedContext.addStaticFields(fromJsonCapturedValues(jsonReader));
            break;
          case THROWABLE:
            capturedContext.addThrowable(throwableAdapter.fromJson(jsonReader));
            break;
          case CAPTURE_EXPRESSIONS:
            for (CapturedContext.CapturedValue value : fromJsonCapturedValues(jsonReader)) {
              capturedContext.addCaptureExpression(value);
            }
            break;
          case NOT_CAPTURED_REASON:
            String reason = jsonReader.nextString();
            throw new IllegalArgumentException("Not captured reason: " + reason);
          default:
            throw new IllegalArgumentException("Unknown field name for Captures object: " + name);
        }
      }
      jsonReader.endObject();
      return capturedContext;
    }

    private void fromJsonFields(JsonReader jsonReader, CapturedContext capturedContext)
        throws IOException {
      jsonReader.beginObject();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        switch (name) {
          case TYPE:
            {
              jsonReader.nextString();
              break;
            }
          case NOT_CAPTURED_REASON:
            {
              jsonReader.nextString();
              break;
            }
          default:
            throw new IllegalArgumentException("Unknown field name for 'this' object: " + name);
        }
      }
      jsonReader.endObject();
    }

    private CapturedContext.CapturedValue[] fromJsonCapturedValues(JsonReader jsonReader)
        throws IOException {
      jsonReader.beginObject();
      List<CapturedContext.CapturedValue> values = new ArrayList<>();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        if (NOT_CAPTURED_REASON.equals(name)) {
          jsonReader.nextString();
          continue;
        }
        CapturedContext.CapturedValue capturedValue = valueAdapter.fromJson(jsonReader);
        if (capturedValue != null) {
          capturedValue.setName(name);
          values.add(capturedValue);
        }
      }
      jsonReader.endObject();
      return values.toArray(EMPTY_VALUES_ARRAY);
    }
  }

  public static class CapturedValueAdapter extends MoshiSnapshotHelper.CapturedValueAdapter {
    @Override
    public CapturedContext.CapturedValue fromJson(JsonReader jsonReader) throws IOException {
      jsonReader.beginObject();
      String type = null;
      Object value = null;
      String notCapturedReason = null;
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        switch (name) {
          case TYPE:
            type = jsonReader.nextString();
            break;
          case VALUE:
            String strValue = jsonReader.nextString();
            value = convertPrimitive(strValue, type);
            break;
          case FIELDS:
            jsonReader.beginObject();
            Map<String, CapturedContext.CapturedValue> fields = new HashMap<>();
            while (jsonReader.hasNext()) {
              String fieldName = jsonReader.nextName();
              if (NOT_CAPTURED_REASON.equals(fieldName)) {
                notCapturedReason = jsonReader.nextString();
                continue;
              }
              CapturedContext.CapturedValue fieldValue = fromJson(jsonReader);
              fields.put(fieldName, fieldValue);
            }
            jsonReader.endObject();
            value = fields;
            break;
          case ELEMENTS:
            {
              if (type == null) {
                throw new RuntimeException("type is null");
              }
              jsonReader.beginArray();
              List<CapturedContext.CapturedValue> values = new ArrayList<>();
              while (jsonReader.hasNext()) {
                CapturedContext.CapturedValue elementValue = fromJson(jsonReader);
                values.add(elementValue);
              }
              jsonReader.endArray();
              if (type.equals(List.class.getTypeName())
                  || type.equals(ArrayList.class.getTypeName())
                  || type.equals("java.util.Collections$UnmodifiableRandomAccessList")) {
                List<Object> list = new ArrayList<>();
                for (CapturedContext.CapturedValue cValue : values) {
                  list.add(cValue.getValue());
                }
                value = list;
              } else if (type.endsWith("[]")) {
                String componentType = type.substring(0, type.indexOf('['));
                if (SerializerWithLimits.isPrimitive(componentType)) {
                  value = createPrimitiveArray(componentType, values);
                } else {
                  value = values.stream().map(CapturedContext.CapturedValue::getValue).toArray();
                }
              } else if (type.equals("java.util.Collections$EmptyList")) {
                value = Collections.emptyList();
              } else {
                throw new RuntimeException("Cannot deserialize type: " + type);
              }
              break;
            }
          case ENTRIES:
            {
              jsonReader.beginArray();
              List<CapturedContext.CapturedValue> values = new ArrayList<>();
              while (jsonReader.hasNext()) {
                jsonReader.beginArray();
                CapturedContext.CapturedValue elementValue = fromJson(jsonReader);
                values.add(elementValue);
                elementValue = fromJson(jsonReader);
                values.add(elementValue);
                jsonReader.endArray();
              }
              jsonReader.endArray();
              Map<Object, Object> entries = new HashMap<>();
              for (int i = 0; i < values.size(); i += 2) {
                Object entryKey = values.get(i).getValue();
                if (i + 1 >= values.size()) {
                  break;
                }
                Object entryValue = values.get(i + 1).getValue();
                entries.put(entryKey, entryValue);
              }
              value = entries;
              break;
            }
          case IS_NULL:
            jsonReader.nextBoolean();
            value = null;
            break;
          case NOT_CAPTURED_REASON:
            notCapturedReason = jsonReader.nextString();
            break;
          case TRUNCATED:
            boolean truncated = jsonReader.nextBoolean();
            if (truncated) {
              notCapturedReason = "truncated";
            }
            break;
          case SIZE:
            jsonReader.nextString(); // consume size value
            break;
          default:
            throw new RuntimeException("Unknown attribute: " + name);
        }
      }
      jsonReader.endObject();
      return CapturedContext.CapturedValue.raw(type, value, notCapturedReason);
    }

    private Object createPrimitiveArray(
        String componentType, List<CapturedContext.CapturedValue> values) {
      switch (componentType) {
        case "byte":
          {
            byte[] bytes = new byte[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              bytes[i++] = (Byte) capturedValue.getValue();
            }
            return bytes;
          }
        case "boolean":
          {
            boolean[] booleans = new boolean[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              booleans[i++] = (Boolean) capturedValue.getValue();
            }
            return booleans;
          }
        case "short":
          {
            short[] shorts = new short[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              shorts[i++] = (Short) capturedValue.getValue();
            }
            return shorts;
          }
        case "char":
          {
            char[] chars = new char[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              chars[i++] = (Character) capturedValue.getValue();
            }
            return chars;
          }
        case "int":
          {
            int[] ints = new int[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              ints[i++] = (Integer) capturedValue.getValue();
            }
            return ints;
          }
        case "long":
          {
            long[] longs = new long[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              longs[i++] = (Long) capturedValue.getValue();
            }
            return longs;
          }
        case "float":
          {
            float[] floats = new float[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              floats[i++] = (Float) capturedValue.getValue();
            }
            return floats;
          }
        case "double":
          {
            double[] doubles = new double[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              doubles[i++] = (Double) capturedValue.getValue();
            }
            return doubles;
          }
        case "java.lang.String":
          {
            String[] strings = new String[values.size()];
            int i = 0;
            for (CapturedContext.CapturedValue capturedValue : values) {
              strings[i++] = (String) capturedValue.getValue();
            }
            return strings;
          }
        default:
          throw new RuntimeException("unsupported primitive type: " + componentType);
      }
    }

    private static Object convertPrimitive(String strValue, String type) {
      if (type == null) {
        return null;
      }
      switch (type) {
        case "byte":
        case "java.lang.Byte":
          return Byte.parseByte(strValue);
        case "short":
        case "java.lang.Short":
          return Short.parseShort(strValue);
        case "char":
        case "java.lang.Character":
          return strValue.charAt(0);
        case "int":
        case "java.lang.Integer":
          return Integer.parseInt(strValue);
        case "long":
        case "java.lang.Long":
          return Long.parseLong(strValue);
        case "boolean":
        case "java.lang.Boolean":
          return Boolean.parseBoolean(strValue);
        case "float":
        case "java.lang.Float":
          return Float.parseFloat(strValue);
        case "double":
        case "java.lang.Double":
          return Double.parseDouble(strValue);
        case "String":
        case "java.lang.String":
          return strValue;
      }
      return strValue;
    }
  }

  public static class CapturedThrowableAdapter
      extends MoshiSnapshotHelper.CapturedThrowableAdapter {
    public CapturedThrowableAdapter(Moshi moshi) {
      super(moshi);
    }

    @Override
    public CapturedContext.CapturedThrowable fromJson(JsonReader jsonReader) throws IOException {
      String type = null;
      String message = null;
      List<CapturedStackFrame> stacktrace = null;
      jsonReader.beginObject();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        switch (name) {
          case TYPE:
            type = jsonReader.nextString();
            break;
          case MESSAGE:
            message = jsonReader.nextString();
            break;
          case STACKTRACE:
            stacktrace = stackTraceAdapter.fromJson(jsonReader);
            break;
        }
      }
      jsonReader.endObject();
      return new CapturedContext.CapturedThrowable(type, message, stacktrace, null);
    }
  }

  public static class ProbeDetailsAdapter extends MoshiSnapshotHelper.ProbeDetailsAdapter {
    public ProbeDetailsAdapter(Moshi moshi) {
      super(moshi);
    }

    @Override
    public ProbeImplementation fromJson(JsonReader jsonReader) throws IOException {
      String id = null;
      int version = 0;
      ProbeLocation location = null;
      jsonReader.beginObject();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        switch (name) {
          case ID:
            id = jsonReader.nextString();
            break;
          case VERSION:
            version = jsonReader.nextInt();
            break;
          case LOCATION:
            location = probeLocationAdapter.fromJson(jsonReader);
            break;
          default:
            throw new RuntimeException("Unknown attribute: " + name);
        }
      }
      jsonReader.endObject();
      return new ProbeImplementation.NoopProbeImplementation(new ProbeId(id, version), location);
    }
  }
}
