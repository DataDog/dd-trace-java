package com.datadog.debugger.util;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.FieldExtractor;
import datadog.trace.bootstrap.debugger.Fields;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper for creating Moshi adapters for (de)serializing snapshots */
public class MoshiSnapshotHelper {
  public static final String CAPTURES = "captures";
  public static final String ENTRY = "entry";
  public static final String RETURN = "return";
  public static final String LINES = "lines";
  public static final String CAUGHT_EXCEPTIONS = "caughtExceptions";
  public static final String ARGUMENTS = "arguments";
  public static final String LOCALS = "locals";
  public static final String THROWABLE = "throwable";
  public static final String THIS = "this";
  public static final String NOT_CAPTURED_REASON = "notCapturedReason";
  public static final String FIELD_COUNT_REASON = "fieldCount";
  public static final String COLLECTION_SIZE_REASON = "collectionSize";
  public static final String DEPTH_REASON = "depth";
  public static final String TYPE = "type";
  public static final String VALUE = "value";
  public static final String FIELDS = "fields";
  public static final String ELEMENTS = "elements";
  public static final String ENTRIES = "entries";
  public static final String IS_NULL = "isNull";
  public static final String TRUNCATED = "truncated";
  public static final String SIZE = "size";

  public static class SnapshotJsonFactory implements JsonAdapter.Factory {
    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> set, Moshi moshi) {
      if (Types.equals(type, Snapshot.Captures.class)) {
        return new CapturesAdapter(moshi);
      }
      if (Types.equals(type, Snapshot.CapturedValue.class)) {
        return new CapturedValueAdapter();
      }
      if (Types.equals(type, Snapshot.CapturedContext.class)) {
        return new CapturedContextAdapter(moshi);
      }
      return null;
    }
  }

  private static class CapturesAdapter extends JsonAdapter<Snapshot.Captures> {
    private final JsonAdapter<Snapshot.CapturedContext> capturedContextAdapter;
    private final JsonAdapter<Map<Integer, Snapshot.CapturedContext>> linesAdapter;
    private final JsonAdapter<List<Snapshot.CapturedThrowable>> caughtExceptionsAdapter;

    public CapturesAdapter(Moshi moshi) {
      capturedContextAdapter = new CapturedContextAdapter(moshi);
      linesAdapter =
          moshi.adapter(
              Types.newParameterizedType(Map.class, Integer.class, Snapshot.CapturedContext.class));
      caughtExceptionsAdapter =
          moshi.adapter(Types.newParameterizedType(List.class, Snapshot.CapturedThrowable.class));
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

    @Override
    public void toJson(JsonWriter jsonWriter, Snapshot.Captures captures) throws IOException {
      if (captures == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.beginObject();
      jsonWriter.name(ENTRY);
      capturedContextAdapter.toJson(jsonWriter, captures.getEntry());
      jsonWriter.name(LINES);
      linesAdapter.toJson(jsonWriter, captures.getLines());
      jsonWriter.name(RETURN);
      capturedContextAdapter.toJson(jsonWriter, captures.getReturn());
      jsonWriter.name(CAUGHT_EXCEPTIONS);
      caughtExceptionsAdapter.toJson(jsonWriter, captures.getCaughtExceptions());
      jsonWriter.endObject();
    }
  }

  private static class CapturedContextAdapter extends JsonAdapter<Snapshot.CapturedContext> {
    private static final Snapshot.CapturedValue[] EMPTY_VALUES_ARRAY =
        new Snapshot.CapturedValue[0];

    private final JsonAdapter<Snapshot.CapturedThrowable> throwableAdapter;
    private final JsonAdapter<Snapshot.CapturedValue> valueAdapter;

    public CapturedContextAdapter(Moshi moshi) {
      valueAdapter = new CapturedValueAdapter();
      throwableAdapter = moshi.adapter(Snapshot.CapturedThrowable.class);
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

    @Override
    public void toJson(JsonWriter jsonWriter, Snapshot.CapturedContext capturedContext)
        throws IOException {
      if (capturedContext == null) {
        jsonWriter.nullValue();
        return;
      }
      // need to 'freeze' the context before serializing it
      capturedContext.freeze();
      jsonWriter.beginObject();
      jsonWriter.name(ARGUMENTS);
      jsonWriter.beginObject();
      if (capturedContext.getFields() != null && !capturedContext.getFields().isEmpty()) {
        jsonWriter.name(THIS);
        jsonWriter.beginObject();
        jsonWriter.name(TYPE);
        jsonWriter.value(capturedContext.getThisClassName());
        jsonWriter.name(FIELDS);
        jsonWriter.beginObject();
        boolean complete =
            toJsonCapturedValues(
                jsonWriter, capturedContext.getFields(), capturedContext.getLimits());
        jsonWriter.endObject(); // FIELDS
        if (!complete) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(FIELD_COUNT_REASON);
        }
        jsonWriter.endObject(); // THIS
      }
      boolean completeArgs =
          toJsonCapturedValues(
              jsonWriter, capturedContext.getArguments(), capturedContext.getLimits());
      jsonWriter.endObject(); // ARGUMENTS
      jsonWriter.name(LOCALS);
      jsonWriter.beginObject();
      boolean completeLocals =
          toJsonCapturedValues(
              jsonWriter, capturedContext.getLocals(), capturedContext.getLimits());
      jsonWriter.endObject(); // LOCALS
      if (!completeArgs || !completeLocals) {
        jsonWriter.name(NOT_CAPTURED_REASON);
        jsonWriter.value(FIELD_COUNT_REASON);
      }
      jsonWriter.name(THROWABLE);
      throwableAdapter.toJson(jsonWriter, capturedContext.getThrowable());
      // TODO add static fields
      // jsonWriter.name("staticFields");
      jsonWriter.endObject();
    }

    /** @return true if all items where serialized or whether we reach the max field count */
    private boolean toJsonCapturedValues(
        JsonWriter jsonWriter, Map<String, Snapshot.CapturedValue> map, Limits limits)
        throws IOException {
      if (map == null) {
        return true;
      }
      int count = 0;
      for (Map.Entry<String, Snapshot.CapturedValue> entry : map.entrySet()) {
        if (count >= limits.maxFieldCount) {
          return false;
        }
        jsonWriter.name(entry.getKey());
        Snapshot.CapturedValue capturedValue = entry.getValue();
        jsonWriter.value(
            Okio.buffer(
                Okio.source(
                    new ByteArrayInputStream(
                        capturedValue.getStrValue().getBytes(StandardCharsets.UTF_8)))));
        count++;
      }
      return true;
    }
  }

  public static class CapturedValueAdapter extends JsonAdapter<Snapshot.CapturedValue> {

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
                if (ValueSerializer.isPrimitive(componentType)) {
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

    @Override
    public void toJson(JsonWriter jsonWriter, Snapshot.CapturedValue capturedValue)
        throws IOException {
      if (capturedValue == null) {
        jsonWriter.nullValue();
      }
      serializeValue(
          jsonWriter, capturedValue.getValue(), capturedValue.getType(), capturedValue.getLimits());
    }

    private void serializeValue(JsonWriter jsonWriter, Object value, String type, Limits limits)
        throws IOException {
      ValueSerializer serializer = new ValueSerializer(new JsonTypeSerializer(jsonWriter));
      try {
        serializer.serialize(value, type, limits);
      } catch (Exception ex) {
        throw new IOException(ex);
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

    private static class JsonTypeSerializer implements ValueSerializer.TypeSerializer {
      private static final Logger LOG = LoggerFactory.getLogger(JsonTypeSerializer.class);

      private final JsonWriter jsonWriter;

      public JsonTypeSerializer(JsonWriter jsonWriter) {
        this.jsonWriter = jsonWriter;
      }

      @Override
      public void prologue(Object value, String type) throws Exception {
        jsonWriter.beginObject();
        jsonWriter.name(TYPE);
        jsonWriter.value(type);
      }

      @Override
      public void epilogue(Object value) throws IOException {
        jsonWriter.endObject();
      }

      @Override
      public void nullValue() throws Exception {
        jsonWriter.name(IS_NULL);
        jsonWriter.value(true);
      }

      @Override
      public void string(String value, boolean isComplete, int originalLength) throws Exception {
        jsonWriter.name(VALUE);
        jsonWriter.value(value);
        if (!isComplete) {
          jsonWriter.name(TRUNCATED);
          jsonWriter.value(true);
          jsonWriter.name(SIZE);
          jsonWriter.value(String.valueOf(originalLength));
        }
      }

      @Override
      public void primitiveValue(Object value) throws Exception {
        jsonWriter.name(VALUE);
        if (WellKnownClasses.isToStringSafe(value.getClass().getTypeName())) {
          jsonWriter.value(String.valueOf(value));
        } else {
          throw new IOException("Cannot convert value: " + value);
        }
      }

      @Override
      public void arrayPrologue(Object value) throws Exception {
        jsonWriter.name(ELEMENTS);
        jsonWriter.beginArray();
      }

      @Override
      public void arrayEpilogue(Object value, boolean isComplete, int arraySize) throws Exception {
        jsonWriter.endArray();
        if (!isComplete) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(COLLECTION_SIZE_REASON);
        }
        jsonWriter.name(SIZE);
        jsonWriter.value(String.valueOf(arraySize));
      }

      @Override
      public void primitiveArrayElement(String value, String type) throws Exception {
        jsonWriter.beginObject();
        jsonWriter.name(TYPE);
        jsonWriter.value(type);
        jsonWriter.name(VALUE);
        jsonWriter.value(value);
        jsonWriter.endObject();
      }

      @Override
      public void collectionPrologue(Object value) throws Exception {
        jsonWriter.name(ELEMENTS);
        jsonWriter.beginArray();
      }

      @Override
      public void collectionEpilogue(Object value, boolean isComplete, int size) throws Exception {
        jsonWriter.endArray();
        if (!isComplete) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(COLLECTION_SIZE_REASON);
        }
        jsonWriter.name(SIZE);
        jsonWriter.value(String.valueOf(size));
      }

      @Override
      public void mapPrologue(Object value) throws Exception {
        jsonWriter.name(ENTRIES);
        jsonWriter.beginArray();
      }

      @Override
      public void mapEntryPrologue(Map.Entry<?, ?> entry) throws Exception {
        jsonWriter.beginArray();
      }

      @Override
      public void mapEntryEpilogue(Map.Entry<?, ?> entry) throws Exception {
        jsonWriter.endArray();
      }

      @Override
      public void mapEpilogue(Map<?, ?> map, boolean isComplete) throws Exception {
        jsonWriter.endArray();
        if (!isComplete) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(COLLECTION_SIZE_REASON);
        }
        jsonWriter.name(SIZE);
        jsonWriter.value(String.valueOf(map.size()));
      }

      @Override
      public void objectValue(Object value, ValueSerializer valueSerializer, Limits limits)
          throws Exception {
        jsonWriter.name(FIELDS);
        jsonWriter.beginObject();
        Fields.ProcessField onField =
            (field, val, maxDepth) -> {
              try {
                jsonWriter.name(field.getName());
                Limits newLimits = Limits.decDepthLimits(maxDepth, limits);
                String typeName;
                if (ValueSerializer.isPrimitive(field.getType().getTypeName())) {
                  typeName = field.getType().getTypeName();
                } else {
                  typeName =
                      val != null ? val.getClass().getTypeName() : field.getType().getTypeName();
                }
                valueSerializer.serialize(
                    val instanceof Snapshot.CapturedValue
                        ? ((Snapshot.CapturedValue) val).getValue()
                        : val,
                    typeName,
                    newLimits);
              } catch (Exception ex) {
                LOG.debug("Exception when extracting field={}", field.getName(), ex);
              }
            };
        BiConsumer<Exception, Field> exHandling =
            (ex, field) -> {
              String fieldName = field.getName();
              try {
                jsonWriter.name(fieldName);
                jsonWriter.beginObject();
                jsonWriter.name(TYPE);
                jsonWriter.value(field.getType().getTypeName());
                jsonWriter.name(NOT_CAPTURED_REASON);
                jsonWriter.value(ex.toString());
                jsonWriter.endObject();
              } catch (IOException e) {
                LOG.debug("Error during serializing reason for failed field extraction", e);
              }
            };
        Consumer<Field> maxFieldCount =
            (field) -> {
              try {
                jsonWriter.name(NOT_CAPTURED_REASON);
                jsonWriter.value(FIELD_COUNT_REASON);
              } catch (IOException e) {
                LOG.debug("Error during serializing reason for reaching max field count", e);
              }
            };
        FieldExtractor.extract(value, limits, onField, exHandling, maxFieldCount);
        jsonWriter.endObject();
      }

      @Override
      public void reachedMaxDepth() throws Exception {
        jsonWriter.name(NOT_CAPTURED_REASON);
        jsonWriter.value(DEPTH_REASON);
      }
    }
  }
}
