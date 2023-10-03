package com.datadog.iast.model.json;

import com.squareup.moshi.JsonWriter;
import datadog.trace.api.Config;
import java.io.IOException;
import javax.annotation.Nullable;

public class TruncatedWriter {

  private static final int VALUE_MAX_LENGTH = Config.get().getIastTruncationMaxValueLength();

  private static final String TRUNCATED = "truncated";
  private static final String RIGHT = "right";

  private final JsonWriter delegated;

  public TruncatedWriter(JsonWriter writer) {
    this.delegated = writer;
  }

  public void beginArray() throws IOException {
    delegated.beginArray();
  }

  public void endArray() throws IOException {
    delegated.endArray();
  }

  public void beginObject() throws IOException {
    delegated.beginObject();
  }

  public void endObject() throws IOException {
    delegated.endObject();
  }

  public void name(String name) throws IOException {
    delegated.name(name);
  }

  public void value(@Nullable String value) throws IOException {
    if (value != null && value.length() > VALUE_MAX_LENGTH) {
      delegated.value(value.substring(0, VALUE_MAX_LENGTH));
      delegated.name(TRUNCATED);
      delegated.value(RIGHT);
    } else {
      delegated.value(value);
    }
  }

  public void nullValue() throws IOException {
    delegated.nullValue();
  }

  public void value(boolean value) throws IOException {
    delegated.value(value);
  }

  public void value(@Nullable Boolean value) throws IOException {
    delegated.value(value);
  }

  public void value(double value) throws IOException {
    delegated.value(value);
  }

  public void value(long value) throws IOException {
    delegated.value(value);
  }

  public void value(@Nullable Number value) throws IOException {
    delegated.value(value);
  }

  public void valueSink() throws IOException {
    delegated.valueSink();
  }

  public void close() throws IOException {
    delegated.close();
  }

  public void flush() throws IOException {
    delegated.flush();
  }

  public JsonWriter getDelegated() {
    return delegated;
  }
}
