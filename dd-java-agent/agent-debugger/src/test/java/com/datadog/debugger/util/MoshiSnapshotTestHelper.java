package com.datadog.debugger.util;

import static com.datadog.debugger.util.MoshiSnapshotHelper.ARGUMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.CAUGHT_EXCEPTIONS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ELEMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ENTRIES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ENTRY;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELDS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.IS_NULL;
import static com.datadog.debugger.util.MoshiSnapshotHelper.LINES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.LOCALS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.RETURN;
import static com.datadog.debugger.util.MoshiSnapshotHelper.SIZE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.THROWABLE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TRUNCATED;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TYPE;
import static com.datadog.debugger.util.MoshiSnapshotHelper.VALUE;

import com.datadog.debugger.el.ProbeCondition;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MoshiSnapshotTestHelper {

  public static class SnapshotJsonFactory implements JsonAdapter.Factory {
    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> set, Moshi moshi) {
      if (Types.equals(type, Snapshot.Captures.class)) {
        return new MoshiSnapshotTestHelper.CapturesAdapter(
            moshi, new CapturedContextAdapter(moshi, new CapturedValueAdapter()));
      }
      if (Types.equals(type, Snapshot.CapturedValue.class)) {
        return new MoshiSnapshotTestHelper.CapturedValueAdapter();
      }
      if (Types.equals(type, Snapshot.CapturedContext.class)) {
        return new MoshiSnapshotTestHelper.CapturedContextAdapter(
            moshi, new CapturedValueAdapter());
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

  private static class CapturesAdapter extends MoshiSnapshotHelper.CapturesAdapter {

    public CapturesAdapter(
        Moshi moshi, JsonAdapter<Snapshot.CapturedContext> capturedContextAdapter) {
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
            Map<Integer, Snapshot.CapturedContext> map = linesAdapter.fromJson(jsonReader);
            if (map != null) {
              map.forEach(captures::addLine);
            }
            break;
          case CAUGHT_EXCEPTIONS:
            List<Snapshot.CapturedThrowable> capturedThrowables =
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
    private static final Snapshot.CapturedValue[] EMPTY_VALUES_ARRAY =
        new Snapshot.CapturedValue[0];

    public CapturedContextAdapter(Moshi moshi, JsonAdapter<Snapshot.CapturedValue> valueAdapter) {
      super(moshi, valueAdapter);
    }

    @Override
    public Snapshot.CapturedContext fromJson(JsonReader jsonReader) throws IOException {
      jsonReader.beginObject();
      Snapshot.CapturedContext capturedContext = new Snapshot.CapturedContext();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        switch (name) {
          case ARGUMENTS:
            jsonReader.beginObject();
            List<Snapshot.CapturedValue> argValues = new ArrayList<>();
            while (jsonReader.hasNext()) {
              String argName = jsonReader.peekJson().nextName();
              if ("this".equals(argName)) {
                jsonReader.nextName(); // consume "this"
                fromJsonFields(jsonReader, capturedContext);
                continue;
              }
              argName = jsonReader.nextName();
              Snapshot.CapturedValue capturedValue = valueAdapter.fromJson(jsonReader);
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
          case THROWABLE:
            capturedContext.addThrowable(throwableAdapter.fromJson(jsonReader));
            break;
          default:
            throw new IllegalArgumentException("Unknown field name for Captures object: " + name);
        }
      }
      jsonReader.endObject();
      return capturedContext;
    }

    private void fromJsonFields(JsonReader jsonReader, Snapshot.CapturedContext capturedContext)
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
          case FIELDS:
            {
              capturedContext.addFields(fromJsonCapturedValues(jsonReader));
              break;
            }
          default:
            throw new IllegalArgumentException("Unknown field name for 'this' object: " + name);
        }
      }
      jsonReader.endObject();
    }

    private Snapshot.CapturedValue[] fromJsonCapturedValues(JsonReader jsonReader)
        throws IOException {
      jsonReader.beginObject();
      List<Snapshot.CapturedValue> values = new ArrayList<>();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        if (NOT_CAPTURED_REASON.equals(name)) {
          jsonReader.nextString();
          continue;
        }
        Snapshot.CapturedValue capturedValue = valueAdapter.fromJson(jsonReader);
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
    public Snapshot.CapturedValue fromJson(JsonReader jsonReader) throws IOException {
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
            Map<String, Snapshot.CapturedValue> fields = new HashMap<>();
            while (jsonReader.hasNext()) {
              String fieldName = jsonReader.nextName();
              if (NOT_CAPTURED_REASON.equals(fieldName)) {
                notCapturedReason = jsonReader.nextString();
                continue;
              }
              Snapshot.CapturedValue fieldValue = fromJson(jsonReader);
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
              List<Snapshot.CapturedValue> values = new ArrayList<>();
              while (jsonReader.hasNext()) {
                Snapshot.CapturedValue elementValue = fromJson(jsonReader);
                values.add(elementValue);
              }
              jsonReader.endArray();
              if (type.equals(List.class.getTypeName())
                  || type.equals(ArrayList.class.getTypeName())
                  || type.equals("java.util.Collections$UnmodifiableRandomAccessList")) {
                List<Object> list = new ArrayList<>();
                for (Snapshot.CapturedValue cValue : values) {
                  list.add(cValue.getValue());
                }
                value = list;
              } else if (type.endsWith("[]")) {
                String componentType = type.substring(0, type.indexOf('['));
                if (SerializerWithLimits.isPrimitive(componentType)) {
                  value = createPrimitiveArray(componentType, values);
                } else {
                  value = values.stream().map(Snapshot.CapturedValue::getValue).toArray();
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
              List<Snapshot.CapturedValue> values = new ArrayList<>();
              while (jsonReader.hasNext()) {
                jsonReader.beginArray();
                Snapshot.CapturedValue elementValue = fromJson(jsonReader);
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
      return Snapshot.CapturedValue.raw(type, value, notCapturedReason);
    }

    private Object createPrimitiveArray(String componentType, List<Snapshot.CapturedValue> values) {
      switch (componentType) {
        case "byte":
          {
            byte[] bytes = new byte[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
              bytes[i++] = (Byte) capturedValue.getValue();
            }
            return bytes;
          }
        case "boolean":
          {
            boolean[] booleans = new boolean[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
              booleans[i++] = (Boolean) capturedValue.getValue();
            }
            return booleans;
          }
        case "short":
          {
            short[] shorts = new short[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
              shorts[i++] = (Short) capturedValue.getValue();
            }
            return shorts;
          }
        case "char":
          {
            char[] chars = new char[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
              chars[i++] = (Character) capturedValue.getValue();
            }
            return chars;
          }
        case "int":
          {
            int[] ints = new int[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
              ints[i++] = (Integer) capturedValue.getValue();
            }
            return ints;
          }
        case "long":
          {
            long[] longs = new long[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
              longs[i++] = (Long) capturedValue.getValue();
            }
            return longs;
          }
        case "float":
          {
            float[] floats = new float[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
              floats[i++] = (Float) capturedValue.getValue();
            }
            return floats;
          }
        case "double":
          {
            double[] doubles = new double[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
              doubles[i++] = (Double) capturedValue.getValue();
            }
            return doubles;
          }
        case "java.lang.String":
          {
            String[] strings = new String[values.size()];
            int i = 0;
            for (Snapshot.CapturedValue capturedValue : values) {
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
      return null;
    }
  }
}
