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
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper for creating Moshi adapters for (de)serializing snapshots */
public class MoshiSnapshotHelper {
  private static final Logger LOG = LoggerFactory.getLogger(MoshiSnapshotHelper.class);
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

  public static boolean isPrimitive(String type) {
    switch (type) {
      case "byte":
      case "short":
      case "char":
      case "int":
      case "long":
      case "boolean":
      case "float":
      case "double":
      case "java.lang.Byte":
      case "java.lang.Short":
      case "java.lang.Character":
      case "java.lang.Integer":
      case "java.lang.Long":
      case "java.lang.Boolean":
      case "java.lang.Float":
      case "java.lang.Double":
      case "String":
      case "java.lang.String":
        return true;
    }
    return false;
  }

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
                capturedContext.addFields(fromJsonCapturedValues(jsonReader));
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

    private Snapshot.CapturedValue[] fromJsonCapturedValues(JsonReader jsonReader)
        throws IOException {
      jsonReader.beginObject();
      List<Snapshot.CapturedValue> values = new ArrayList<>();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
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
        toJsonCapturedValues(jsonWriter, capturedContext.getFields(), capturedContext.getLimits());
        jsonWriter.endObject();
      }
      toJsonCapturedValues(jsonWriter, capturedContext.getArguments(), capturedContext.getLimits());
      jsonWriter.endObject();
      jsonWriter.name(LOCALS);
      jsonWriter.beginObject();
      toJsonCapturedValues(jsonWriter, capturedContext.getLocals(), capturedContext.getLimits());
      jsonWriter.endObject();
      jsonWriter.name(THROWABLE);
      throwableAdapter.toJson(jsonWriter, capturedContext.getThrowable());
      // TODO add static fields
      // jsonWriter.name("staticFields");
      jsonWriter.endObject();
    }

    private void toJsonCapturedValues(
        JsonWriter jsonWriter, Map<String, Snapshot.CapturedValue> map, Limits limits)
        throws IOException {
      if (map == null) {
        return;
      }
      int count = 0;
      for (Map.Entry<String, Snapshot.CapturedValue> entry : map.entrySet()) {
        if (count >= limits.maxFieldCount) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(FIELD_COUNT_REASON);
          break;
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
              if (type.equals(List.class.getName()) || type.equals(ArrayList.class.getName())) {
                List<Object> list = new ArrayList<>();
                for (Snapshot.CapturedValue cValue : values) {
                  list.add(cValue.getValue());
                }
                value = list;
              } else if (type.endsWith("[]")) {
                String componentType = type.substring(0, type.indexOf('['));
                if (isPrimitive(componentType)) {
                  value = createPrimitiveArray(componentType, values);
                } else {
                  value = values.stream().map(Snapshot.CapturedValue::getValue).toArray();
                }
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
              if (type.equals(Map.class.getName()) || type.equals(HashMap.class.getName())) {
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
              } else {
                throw new RuntimeException("Cannot deserialize type: " + type);
              }
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
      jsonWriter.beginObject();
      jsonWriter.name(TYPE);
      jsonWriter.value(type);
      if (value == null) {
        jsonWriter.name(IS_NULL);
        jsonWriter.value(true);
      } else if (isPrimitive(type)) {
        jsonWriter.name(VALUE);
        writePrimitive(jsonWriter, value, limits);
      } else if (value.getClass().isArray() && (limits.maxReferenceDepth > 0)) {
        jsonWriter.name(ELEMENTS);
        jsonWriter.beginArray();
        SerializationResult result;
        if (value.getClass().getComponentType().isPrimitive()) {
          result = serializePrimitiveArray(jsonWriter, value, limits);
        } else {
          result = serializeObjectArray(jsonWriter, (Object[]) value, limits);
        }
        jsonWriter.endArray();
        if (!result.isComplete) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(COLLECTION_SIZE_REASON);
        }
        jsonWriter.name(SIZE);
        jsonWriter.value(String.valueOf(result.size));
      } else if (value instanceof Collection && (limits.maxReferenceDepth > 0)) {
        Collection<?> col = (Collection<?>) value;
        jsonWriter.name(ELEMENTS);
        jsonWriter.beginArray();
        SerializationResult result = serializeCollection(jsonWriter, col, limits);
        jsonWriter.endArray();
        if (!result.isComplete) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(COLLECTION_SIZE_REASON);
        }
        jsonWriter.name(SIZE);
        jsonWriter.value(String.valueOf(result.size));
      } else if (value instanceof Map && (limits.maxReferenceDepth > 0)) {
        Map<?, ?> map = (Map<?, ?>) value;
        Set<? extends Map.Entry<?, ?>> entries = map.entrySet();
        jsonWriter.name(ENTRIES);
        jsonWriter.beginArray();
        boolean isComplete = serializeMap(jsonWriter, entries, limits);
        jsonWriter.endArray();
        if (!isComplete) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(COLLECTION_SIZE_REASON);
        }
        jsonWriter.name(SIZE);
        jsonWriter.value(String.valueOf(map.size()));
      } else if (limits.maxReferenceDepth > 0) {
        jsonWriter.name(FIELDS);
        jsonWriter.beginObject();
        Fields.ProcessField onField =
            (field, val, maxDepth) -> {
              try {
                jsonWriter.name(field.getName());
                Limits newLimits = Limits.decDepthLimits(maxDepth, limits);
                serializeValue(
                    jsonWriter,
                    val instanceof Snapshot.CapturedValue
                        ? ((Snapshot.CapturedValue) val).getValue()
                        : val,
                    field.getType().getTypeName(),
                    newLimits);
              } catch (IOException ex) {
                LOG.debug("Exception when extracting field={}", field.getName(), ex);
              }
            };
        BiConsumer<Exception, Field> exHandling =
            (ex, field) -> {
              String fieldName = field.getName();
              LOG.debug(
                  "Cannot extract field[{}] from class[{}]",
                  fieldName,
                  field.getDeclaringClass().getName(),
                  ex);
              try {
                jsonWriter.name(fieldName);
                jsonWriter.beginObject();
                jsonWriter.name(TYPE);
                jsonWriter.value(field.getType().getName());
                jsonWriter.name(NOT_CAPTURED_REASON);
                jsonWriter.value(ex.toString());
                jsonWriter.endObject();
              } catch (IOException e) {
                LOG.debug("Error during serializing reason for failed field extraction", e);
              }
            };
        ObjIntConsumer<Field> maxFieldCount =
            (field, maxCount) -> {
              try {
                jsonWriter.name(NOT_CAPTURED_REASON);
                jsonWriter.value(FIELD_COUNT_REASON);
              } catch (IOException e) {
                LOG.debug("Error during serializing reason for reaching max field count", e);
              }
            };
        FieldExtractor.extract(value, limits, onField, exHandling, maxFieldCount);
        jsonWriter.endObject();
      } else {
        jsonWriter.name(NOT_CAPTURED_REASON);
        jsonWriter.value(DEPTH_REASON);
      }
      jsonWriter.endObject();
    }

    private boolean serializeMap(
        JsonWriter jsonWriter, Set<? extends Map.Entry<?, ?>> entries, Limits limits)
        throws IOException {
      int mapSize = entries.size();
      int maxSize = Math.min(mapSize, limits.maxCollectionSize);
      int i = 0;
      Iterator<?> it = entries.iterator();
      while (i < maxSize && it.hasNext()) {
        Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
        jsonWriter.beginArray();
        Object keyObj = entry.getKey();
        Object valObj = entry.getValue();
        serializeValue(
            jsonWriter,
            entry.getKey(),
            entry.getKey().getClass().getName(),
            Limits.decDepthLimits(limits.maxReferenceDepth, limits));
        serializeValue(
            jsonWriter,
            entry.getValue(),
            entry.getValue().getClass().getName(),
            Limits.decDepthLimits(limits.maxReferenceDepth, limits));
        jsonWriter.endArray();
        i++;
      }
      return maxSize == mapSize;
    }

    private SerializationResult serializeCollection(
        JsonWriter jsonWriter, Collection<?> collection, Limits limits) throws IOException {
      // /!\ here we assume that Collection#Size is O(1) /!\
      int colSize = collection.size();
      int maxSize = Math.min(colSize, limits.maxCollectionSize);
      int i = 0;
      Iterator<?> it = collection.iterator();
      while (i < maxSize && it.hasNext()) {
        Object val = it.next();
        serializeValue(
            jsonWriter,
            val,
            val.getClass().getName(),
            Limits.decDepthLimits(limits.maxReferenceDepth, limits));
        i++;
      }
      return new SerializationResult(colSize, maxSize == colSize);
    }

    private SerializationResult serializeObjectArray(
        JsonWriter jsonWriter, Object[] objArray, Limits limits) throws IOException {
      int maxSize = Math.min(objArray.length, limits.maxCollectionSize);
      int i = 0;
      while (i < maxSize) {
        Object val = objArray[i];
        serializeValue(
            jsonWriter,
            val,
            val != null ? val.getClass().getName() : "java.lang.Object",
            Limits.decDepthLimits(limits.maxReferenceDepth, limits));
        i++;
      }
      return new SerializationResult(objArray.length, maxSize == objArray.length);
    }

    private SerializationResult serializePrimitiveArray(
        JsonWriter jsonWriter, Object value, Limits limits) throws IOException {
      Class<?> componentType = value.getClass().getComponentType();
      if (componentType == long.class) {
        return serializeLongArray(jsonWriter, (long[]) value, limits.maxCollectionSize);
      }
      if (componentType == int.class) {
        return serializeIntArray(jsonWriter, (int[]) value, limits.maxCollectionSize);
      }
      if (componentType == short.class) {
        return serializeShortArray(jsonWriter, (short[]) value, limits.maxCollectionSize);
      }
      if (componentType == char.class) {
        return serializeCharArray(jsonWriter, (char[]) value, limits.maxCollectionSize);
      }
      if (componentType == byte.class) {
        return serializeByteArray(jsonWriter, (byte[]) value, limits.maxCollectionSize);
      }
      if (componentType == boolean.class) {
        return serializeBooleanArray(jsonWriter, (boolean[]) value, limits.maxCollectionSize);
      }
      if (componentType == float.class) {
        return serializeFloatArray(jsonWriter, (float[]) value, limits.maxCollectionSize);
      }
      if (componentType == double.class) {
        return serializeDoubleArray(jsonWriter, (double[]) value, limits.maxCollectionSize);
      }
      throw new IllegalArgumentException("Unsupported primitive array: " + value.getClass());
    }

    private static SerializationResult serializeLongArray(
        JsonWriter jsonWriter, long[] longArray, int maxSize) throws IOException {
      maxSize = Math.min(longArray.length, maxSize);
      int i = 0;
      while (i < maxSize) {
        long val = longArray[i];
        String strVal = String.valueOf(val);
        serializeArrayItem(jsonWriter, "long", strVal);
        i++;
      }
      return new SerializationResult(longArray.length, maxSize == longArray.length);
    }

    private static SerializationResult serializeIntArray(
        JsonWriter jsonWriter, int[] intArray, int maxSize) throws IOException {
      maxSize = Math.min(intArray.length, maxSize);
      int i = 0;
      while (i < maxSize) {
        long val = intArray[i];
        String strVal = String.valueOf(val);
        serializeArrayItem(jsonWriter, "int", strVal);
        i++;
      }
      return new SerializationResult(intArray.length, maxSize == intArray.length);
    }

    private static SerializationResult serializeShortArray(
        JsonWriter jsonWriter, short[] shortArray, int maxSize) throws IOException {
      maxSize = Math.min(shortArray.length, maxSize);
      int i = 0;
      while (i < maxSize) {
        short val = shortArray[i];
        String strVal = String.valueOf(val);
        serializeArrayItem(jsonWriter, "short", strVal);
        i++;
      }
      return new SerializationResult(shortArray.length, maxSize == shortArray.length);
    }

    private static SerializationResult serializeCharArray(
        JsonWriter jsonWriter, char[] charArray, int maxSize) throws IOException {
      maxSize = Math.min(charArray.length, maxSize);
      int i = 0;
      while (i < maxSize) {
        char val = charArray[i];
        String strVal = String.valueOf(val);
        serializeArrayItem(jsonWriter, "char", strVal);
        i++;
      }
      return new SerializationResult(charArray.length, maxSize == charArray.length);
    }

    private static SerializationResult serializeByteArray(
        JsonWriter jsonWriter, byte[] byteArray, int maxSize) throws IOException {
      maxSize = Math.min(byteArray.length, maxSize);
      int i = 0;
      while (i < maxSize) {
        byte val = byteArray[i];
        String strVal = String.valueOf(val);
        serializeArrayItem(jsonWriter, "byte", strVal);
        i++;
      }
      return new SerializationResult(byteArray.length, maxSize == byteArray.length);
    }

    private static SerializationResult serializeBooleanArray(
        JsonWriter jsonWriter, boolean[] booleanArray, int maxSize) throws IOException {
      maxSize = Math.min(booleanArray.length, maxSize);
      int i = 0;
      while (i < maxSize) {
        boolean val = booleanArray[i];
        String strVal = String.valueOf(val);
        serializeArrayItem(jsonWriter, "boolean", strVal);
        i++;
      }
      return new SerializationResult(booleanArray.length, maxSize == booleanArray.length);
    }

    private static SerializationResult serializeFloatArray(
        JsonWriter jsonWriter, float[] floatArray, int maxSize) throws IOException {
      maxSize = Math.min(floatArray.length, maxSize);
      int i = 0;
      while (i < maxSize) {
        float val = floatArray[i];
        String strVal = String.valueOf(val);
        serializeArrayItem(jsonWriter, "float", strVal);
        i++;
      }
      return new SerializationResult(floatArray.length, maxSize == floatArray.length);
    }

    private static SerializationResult serializeDoubleArray(
        JsonWriter jsonWriter, double[] doubleArray, int maxSize) throws IOException {
      maxSize = Math.min(doubleArray.length, maxSize);
      int i = 0;
      while (i < maxSize) {
        double val = doubleArray[i];
        String strVal = String.valueOf(val);
        serializeArrayItem(jsonWriter, "double", strVal);
        i++;
      }
      return new SerializationResult(doubleArray.length, maxSize == doubleArray.length);
    }

    private static void serializeArrayItem(JsonWriter jsonWriter, String type, String value)
        throws IOException {
      jsonWriter.beginObject();
      jsonWriter.name(TYPE);
      jsonWriter.value(type);
      jsonWriter.name(VALUE);
      jsonWriter.value(value);
      jsonWriter.endObject();
    }

    private static void writePrimitive(JsonWriter jsonWriter, Object value, Limits limits)
        throws IOException {
      // primitive values are stored as String
      if (value instanceof String) {
        String strValue = (String) value;
        int originalLength = strValue.length();
        boolean isComplete = true;
        if (originalLength > limits.maxLength) {
          strValue = strValue.substring(0, limits.maxLength);
          isComplete = false;
        }
        jsonWriter.value(strValue);
        if (!isComplete) {
          jsonWriter.name(TRUNCATED);
          jsonWriter.value(true);
          jsonWriter.name(SIZE);
          jsonWriter.value(String.valueOf(originalLength));
        }
      } else if (value instanceof Long
          || value instanceof Integer
          || value instanceof Double
          || value instanceof Boolean
          || value instanceof Byte
          || value instanceof Short
          || value instanceof Float
          || value instanceof Character) {
        jsonWriter.value(String.valueOf(value));
      } else {
        throw new IOException("Cannot convert value: " + value);
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

    private static class SerializationResult {
      final int size;
      final boolean isComplete;

      public SerializationResult(int size, boolean isComplete) {
        this.size = size;
        this.isComplete = isComplete;
      }
    }
  }
}
