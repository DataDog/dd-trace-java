package com.datadog.debugger.util;

import com.datadog.debugger.sink.Snapshot;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
  public static final String CAPTURE_EXPRESSIONS = "captureExpressions";
  public static final String THROWABLE = "throwable";
  public static final String STATIC_FIELDS = "staticFields";
  public static final String THIS = "this";
  public static final String NOT_CAPTURED_REASON = "notCapturedReason";
  public static final String FIELD_COUNT_REASON = "fieldCount";
  public static final String COLLECTION_SIZE_REASON = "collectionSize";
  public static final String TIMEOUT_REASON = "timeout";
  public static final String DEPTH_REASON = "depth";
  public static final String REDACTED_IDENT_REASON = "redactedIdent";
  public static final String REDACTED_TYPE_REASON = "redactedType";
  public static final String TYPE = "type";
  public static final String VALUE = "value";
  public static final String FIELDS = "fields";
  public static final String ELEMENTS = "elements";
  public static final String ENTRIES = "entries";
  public static final String IS_NULL = "isNull";
  public static final String TRUNCATED = "truncated";
  public static final String SIZE = "size";
  public static final String ID = "id";
  public static final String VERSION = "version";
  public static final String LOCATION = "location";
  public static final String MESSAGE = "message";
  public static final String STACKTRACE = "stacktrace";
  private static final Duration TIME_OUT = Duration.ofMillis(200);

  public static class SnapshotJsonFactory implements JsonAdapter.Factory {
    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> set, Moshi moshi) {
      if (Types.equals(type, Snapshot.Captures.class)) {
        return new CapturesAdapter(
            moshi,
            new CapturedContextAdapter(
                moshi, new CapturedValueAdapter(), new CapturedThrowableAdapter(moshi)));
      }
      if (Types.equals(type, CapturedContext.CapturedValue.class)) {
        return new CapturedValueAdapter();
      }
      if (Types.equals(type, CapturedContext.class)) {
        return new CapturedContextAdapter(
            moshi, new CapturedValueAdapter(), new CapturedThrowableAdapter(moshi));
      }
      if (Types.equals(type, ProbeImplementation.class)) {
        return new ProbeDetailsAdapter(moshi);
      }
      return null;
    }
  }

  public static class CapturesAdapter extends JsonAdapter<Snapshot.Captures> {
    protected final JsonAdapter<CapturedContext> capturedContextAdapter;
    protected final JsonAdapter<Map<Integer, CapturedContext>> linesAdapter;
    protected final JsonAdapter<List<CapturedContext.CapturedThrowable>> caughtExceptionsAdapter;

    public CapturesAdapter(Moshi moshi, JsonAdapter<CapturedContext> capturedContextAdapter) {
      this.capturedContextAdapter = capturedContextAdapter;
      linesAdapter =
          moshi.adapter(
              Types.newParameterizedType(Map.class, Integer.class, CapturedContext.class));
      caughtExceptionsAdapter =
          moshi.adapter(
              Types.newParameterizedType(List.class, CapturedContext.CapturedThrowable.class));
    }

    @Override
    public void toJson(JsonWriter jsonWriter, Snapshot.Captures captures) throws IOException {
      if (captures == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.setTag(
          TimeoutChecker.class, new TimeoutChecker(System.currentTimeMillis(), TIME_OUT));
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

  public static class CapturedContextAdapter extends JsonAdapter<CapturedContext> {
    protected final JsonAdapter<CapturedContext.CapturedThrowable> throwableAdapter;
    protected final JsonAdapter<CapturedContext.CapturedValue> valueAdapter;

    public CapturedContextAdapter(
        Moshi moshi,
        JsonAdapter<CapturedContext.CapturedValue> valueAdapter,
        CapturedThrowableAdapter throwableAdapter) {
      this.valueAdapter = valueAdapter;
      this.throwableAdapter = throwableAdapter;
    }

    @Override
    public void toJson(JsonWriter jsonWriter, CapturedContext capturedContext) throws IOException {
      if (capturedContext == null) {
        jsonWriter.nullValue();
        return;
      }
      TimeoutChecker timeoutChecker = jsonWriter.tag(TimeoutChecker.class);
      if (timeoutChecker == null) {
        timeoutChecker = new TimeoutChecker(TIME_OUT);
      }
      // need to 'freeze' the context before serializing it
      capturedContext.freeze(timeoutChecker);
      if (timeoutChecker.isTimedOut(System.currentTimeMillis())) {
        jsonWriter.beginObject();
        jsonWriter.name(NOT_CAPTURED_REASON);
        jsonWriter.value(TIMEOUT_REASON);
        jsonWriter.endObject();
        return;
      }
      jsonWriter.beginObject();
      if (capturedContext.getCaptureExpressions() != null) {
        // only capture expressions are serialized into the snapshot
        jsonWriter.name(CAPTURE_EXPRESSIONS);
        jsonWriter.beginObject();
        SerializationResult resultCaptureExpressions =
            toJsonCapturedValues(
                jsonWriter,
                capturedContext.getCaptureExpressions(),
                capturedContext.getLimits(),
                timeoutChecker);
        jsonWriter.endObject(); // captureExpressions
        handleSerializationResult(jsonWriter, resultCaptureExpressions);
        jsonWriter.endObject();
        return;
      }
      jsonWriter.name(ARGUMENTS);
      jsonWriter.beginObject();
      SerializationResult resultArgs =
          toJsonCapturedValues(
              jsonWriter,
              capturedContext.getArguments(),
              capturedContext.getLimits(),
              timeoutChecker);
      jsonWriter.endObject(); // ARGUMENTS
      jsonWriter.name(LOCALS);
      jsonWriter.beginObject();
      SerializationResult resultLocals =
          toJsonCapturedValues(
              jsonWriter, capturedContext.getLocals(), capturedContext.getLimits(), timeoutChecker);
      jsonWriter.endObject(); // LOCALS
      jsonWriter.name(STATIC_FIELDS);
      jsonWriter.beginObject();
      SerializationResult resultStaticFields =
          toJsonCapturedValues(
              jsonWriter,
              capturedContext.getStaticFields(),
              capturedContext.getLimits(),
              timeoutChecker);
      jsonWriter.endObject();
      handleSerializationResult(jsonWriter, resultLocals, resultArgs, resultStaticFields);
      jsonWriter.name(THROWABLE);
      throwableAdapter.toJson(jsonWriter, capturedContext.getCapturedThrowable());
      jsonWriter.endObject();
    }

    private void handleSerializationResult(JsonWriter jsonWriter, SerializationResult... results)
        throws IOException {
      boolean fieldCountReported = false;
      boolean timeoutReported = false;
      for (SerializationResult result : results) {
        switch (result) {
          case OK:
            break;
          case FIELD_COUNT:
            {
              if (!fieldCountReported) {
                jsonWriter.name(NOT_CAPTURED_REASON);
                jsonWriter.value(FIELD_COUNT_REASON);
                fieldCountReported = true;
              }
              break;
            }
          case TIMEOUT:
            {
              if (!timeoutReported) {
                jsonWriter.name(NOT_CAPTURED_REASON);
                jsonWriter.value(TIMEOUT_REASON);
                timeoutReported = true;
              }
              break;
            }
          default:
            throw new RuntimeException("Unsupported serialization result: " + result);
        }
      }
    }

    enum SerializationResult {
      OK,
      FIELD_COUNT,
      TIMEOUT
    }

    /** @return true if all items where serialized or whether we reach the max field count */
    private SerializationResult toJsonCapturedValues(
        JsonWriter jsonWriter,
        Map<String, CapturedContext.CapturedValue> map,
        Limits limits,
        TimeoutChecker timeoutChecker)
        throws IOException {
      if (map == null) {
        return SerializationResult.OK;
      }
      int count = 0;
      for (Map.Entry<String, CapturedContext.CapturedValue> entry : map.entrySet()) {
        if (count >= limits.maxFieldCount) {
          return SerializationResult.FIELD_COUNT;
        }
        if (timeoutChecker.isTimedOut(System.currentTimeMillis())) {
          return SerializationResult.TIMEOUT;
        }
        jsonWriter.name(entry.getKey());
        CapturedContext.CapturedValue capturedValue = entry.getValue();
        jsonWriter.value(
            Okio.buffer(
                Okio.source(
                    new ByteArrayInputStream(
                        capturedValue.getStrValue().getBytes(StandardCharsets.UTF_8)))));
        count++;
      }
      return SerializationResult.OK;
    }

    @Override
    public CapturedContext fromJson(JsonReader reader) throws IOException {
      // Only used in test, see MoshiSnapshotTestHelper
      throw new IllegalStateException("Should not reach this code.");
    }
  }

  public static class CapturedValueAdapter extends JsonAdapter<CapturedContext.CapturedValue> {
    @Override
    public void toJson(JsonWriter jsonWriter, CapturedContext.CapturedValue capturedValue)
        throws IOException {
      if (capturedValue == null) {
        jsonWriter.nullValue();
        return;
      }
      TimeoutChecker timeoutChecker = capturedValue.getTimeoutChecker();
      if (timeoutChecker == null) {
        timeoutChecker = new TimeoutChecker(Duration.of(10, ChronoUnit.MILLIS));
      }
      Object value = capturedValue.getValue();
      String type = capturedValue.getType();
      Limits limits = capturedValue.getLimits();
      SerializerWithLimits serializer =
          new SerializerWithLimits(new JsonTokenWriter(jsonWriter), timeoutChecker);
      try {
        serializer.serialize(value, type, limits);
      } catch (Exception ex) {
        throw new IOException(ex);
      }
    }

    @Override
    public CapturedContext.CapturedValue fromJson(JsonReader reader) throws IOException {
      // Only used in test, see MoshiSnapshotTestHelper
      throw new IllegalStateException("Should not reach this code.");
    }

    private static class JsonTokenWriter implements SerializerWithLimits.TokenWriter {
      private static final Logger LOGGER = LoggerFactory.getLogger(JsonTokenWriter.class);

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
        String typeName = value.getClass().getTypeName();
        Function<Object, String> toString = WellKnownClasses.getSafeToString(typeName);
        if (toString != null) {
          String strValue = toString.apply(value);
          jsonWriter.value(strValue);
        } else {
          throw new IOException("Cannot convert value from type: " + typeName);
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
      public void mapEpilogue(boolean isComplete, int size) throws Exception {
        jsonWriter.endArray();
        if (!isComplete) {
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(COLLECTION_SIZE_REASON);
        }
        jsonWriter.name(SIZE);
        jsonWriter.value(String.valueOf(size));
      }

      @Override
      public void objectPrologue(Object value) throws Exception {
        jsonWriter.name(FIELDS);
        jsonWriter.beginObject();
      }

      @Override
      public void objectFieldPrologue(String fieldName, Object value, int maxDepth)
          throws Exception {
        jsonWriter.name(fieldName);
      }

      @Override
      public void handleFieldException(Exception ex, Field field) {
        if (LOGGER.isDebugEnabled()) {
          // foldExceptionStackTrace can be expensive, only do it if debug is enabled
          LOGGER.debug(
              "Exception when extracting field={} exception={}",
              field.getName(),
              ExceptionHelper.foldExceptionStackTrace(ex));
        }
        fieldNotCaptured(ex.toString(), field);
      }

      @Override
      public void fieldNotCaptured(String reason, Field field) {
        String fieldName = field.getName();
        try {
          jsonWriter.name(fieldName);
          jsonWriter.beginObject();
          jsonWriter.name(TYPE);
          jsonWriter.value(field.getType().getTypeName());
          jsonWriter.name(NOT_CAPTURED_REASON);
          jsonWriter.value(reason);
          jsonWriter.endObject();
        } catch (IOException e) {
          LOGGER.debug("Serialization error: failed to extract field", e);
        }
      }

      @Override
      public void objectEpilogue(Object value) throws Exception {
        jsonWriter.endObject();
      }

      @Override
      public void notCaptured(SerializerWithLimits.NotCapturedReason reason) throws Exception {
        switch (reason) {
          case MAX_DEPTH:
            {
              jsonWriter.name(NOT_CAPTURED_REASON);
              jsonWriter.value(DEPTH_REASON);
              break;
            }
          case FIELD_COUNT:
            {
              jsonWriter.name(NOT_CAPTURED_REASON);
              jsonWriter.value(FIELD_COUNT_REASON);
              break;
            }
          case TIMEOUT:
            {
              jsonWriter.name(NOT_CAPTURED_REASON);
              jsonWriter.value(TIMEOUT_REASON);
              break;
            }
          case REDACTED_IDENT:
            {
              jsonWriter.name(NOT_CAPTURED_REASON);
              jsonWriter.value(REDACTED_IDENT_REASON);
              break;
            }
          case REDACTED_TYPE:
            {
              jsonWriter.name(NOT_CAPTURED_REASON);
              jsonWriter.value(REDACTED_TYPE_REASON);
              break;
            }
          default:
            throw new RuntimeException("Unsupported NotCapturedReason: " + reason);
        }
      }

      @Override
      public void notCaptured(String reason) throws Exception {
        jsonWriter.name(NOT_CAPTURED_REASON);
        jsonWriter.value(reason);
      }
    }
  }

  public static class CapturedThrowableAdapter
      extends JsonAdapter<CapturedContext.CapturedThrowable> {
    private static final int MAX_EXCEPTION_MESSAGE_LENGTH = 2048;
    protected final JsonAdapter<List<CapturedStackFrame>> stackTraceAdapter;

    public CapturedThrowableAdapter(Moshi moshi) {
      stackTraceAdapter =
          moshi.adapter(Types.newParameterizedType(List.class, CapturedStackFrame.class));
    }

    @Override
    public void toJson(JsonWriter jsonWriter, CapturedContext.CapturedThrowable value)
        throws IOException {
      if (value == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.beginObject();
      jsonWriter.name(TYPE);
      jsonWriter.value(value.getType());
      jsonWriter.name(MESSAGE);
      String msg = value.getMessage();
      if (msg != null && msg.length() > MAX_EXCEPTION_MESSAGE_LENGTH) {
        msg = msg.substring(0, MAX_EXCEPTION_MESSAGE_LENGTH);
      }
      jsonWriter.value(msg);
      jsonWriter.name(STACKTRACE);
      stackTraceAdapter.toJson(jsonWriter, value.getStacktrace());
      jsonWriter.endObject();
    }

    @Override
    public CapturedContext.CapturedThrowable fromJson(JsonReader reader) throws IOException {
      // Only used in test, see MoshiSnapshotTestHelper
      throw new IllegalStateException("Should not reach this code.");
    }
  }

  public static class ProbeDetailsAdapter extends JsonAdapter<ProbeImplementation> {
    protected final JsonAdapter<ProbeLocation> probeLocationAdapter;

    public ProbeDetailsAdapter(Moshi moshi) {
      probeLocationAdapter = moshi.adapter(ProbeLocation.class);
    }

    @Override
    public void toJson(JsonWriter writer, ProbeImplementation value) throws IOException {
      writer.beginObject();
      writer.name(ID);
      writer.value(value.getProbeId().getId());
      writer.name(VERSION);
      writer.value(value.getProbeId().getVersion());
      writer.name(LOCATION);
      probeLocationAdapter.toJson(writer, value.getLocation());
      writer.endObject();
    }

    @Override
    public ProbeImplementation fromJson(JsonReader reader) throws IOException {
      // Only used in test, see MoshiSnapshotTestHelper
      throw new IllegalStateException("Should not reach this code.");
    }
  }
}
