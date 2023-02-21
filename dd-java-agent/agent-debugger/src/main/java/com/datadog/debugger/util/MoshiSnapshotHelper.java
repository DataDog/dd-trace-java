package com.datadog.debugger.util;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return new CapturesAdapter(
            moshi, new CapturedContextAdapter(moshi, new CapturedValueAdapter()));
      }
      if (Types.equals(type, Snapshot.CapturedValue.class)) {
        return new CapturedValueAdapter();
      }
      if (Types.equals(type, Snapshot.CapturedContext.class)) {
        return new CapturedContextAdapter(moshi, new CapturedValueAdapter());
      }
      return null;
    }
  }

  public static class CapturesAdapter extends JsonAdapter<Snapshot.Captures> {
    protected final JsonAdapter<Snapshot.CapturedContext> capturedContextAdapter;
    protected final JsonAdapter<Map<Integer, Snapshot.CapturedContext>> linesAdapter;
    protected final JsonAdapter<List<Snapshot.CapturedThrowable>> caughtExceptionsAdapter;

    public CapturesAdapter(
        Moshi moshi, JsonAdapter<Snapshot.CapturedContext> capturedContextAdapter) {
      this.capturedContextAdapter = capturedContextAdapter;
      linesAdapter =
          moshi.adapter(
              Types.newParameterizedType(Map.class, Integer.class, Snapshot.CapturedContext.class));
      caughtExceptionsAdapter =
          moshi.adapter(Types.newParameterizedType(List.class, Snapshot.CapturedThrowable.class));
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

    @Override
    public Snapshot.Captures fromJson(JsonReader reader) throws IOException {
      // Only used in test, see MoshiSnapshotTestHelper
      throw new IllegalStateException("Should not reach this code.");
    }
  }

  public static class CapturedContextAdapter extends JsonAdapter<Snapshot.CapturedContext> {
    protected final JsonAdapter<Snapshot.CapturedThrowable> throwableAdapter;
    protected final JsonAdapter<Snapshot.CapturedValue> valueAdapter;

    public CapturedContextAdapter(Moshi moshi, JsonAdapter<Snapshot.CapturedValue> valueAdapter) {
      this.valueAdapter = valueAdapter;
      this.throwableAdapter = moshi.adapter(Snapshot.CapturedThrowable.class);
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

    @Override
    public Snapshot.CapturedContext fromJson(JsonReader reader) throws IOException {
      // Only used in test, see MoshiSnapshotTestHelper
      throw new IllegalStateException("Should not reach this code.");
    }
  }

  public static class CapturedValueAdapter extends JsonAdapter<Snapshot.CapturedValue> {

    @Override
    public void toJson(JsonWriter jsonWriter, Snapshot.CapturedValue capturedValue)
        throws IOException {
      if (capturedValue == null) {
        jsonWriter.nullValue();
        return;
      }
      serializeValue(
          jsonWriter, capturedValue.getValue(), capturedValue.getType(), capturedValue.getLimits());
    }

    private void serializeValue(JsonWriter jsonWriter, Object value, String type, Limits limits)
        throws IOException {
      SerializerWithLimits serializer = new SerializerWithLimits(new JsonTokenWriter(jsonWriter));
      try {
        serializer.serialize(value, type, limits);
      } catch (Exception ex) {
        throw new IOException(ex);
      }
    }

    @Override
    public Snapshot.CapturedValue fromJson(JsonReader reader) throws IOException {
      // Only used in test, see MoshiSnapshotTestHelper
      throw new IllegalStateException("Should not reach this code.");
    }

    private static class JsonTokenWriter implements SerializerWithLimits.TokenWriter {
      private static final Logger LOG = LoggerFactory.getLogger(JsonTokenWriter.class);

      private final JsonWriter jsonWriter;

      public JsonTokenWriter(JsonWriter jsonWriter) {
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
      public void objectPrologue(Object value) throws Exception {
        jsonWriter.name(FIELDS);
        jsonWriter.beginObject();
      }

      @Override
      public void objectFieldPrologue(Field field, Object value, int maxDepth) throws Exception {
        jsonWriter.name(field.getName());
      }

      @Override
      public void handleFieldException(Exception ex, Field field) {
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
          LOG.debug("Serialization error: failed to extract field", e);
        }
      }

      @Override
      public void objectMaxFieldCount() {
        try {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(FIELD_COUNT_REASON);
        } catch (IOException e) {
          LOG.debug("Error during serializing reason for reaching max field count", e);
        }
      }

      @Override
      public void objectEpilogue(Object value) throws Exception {
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
