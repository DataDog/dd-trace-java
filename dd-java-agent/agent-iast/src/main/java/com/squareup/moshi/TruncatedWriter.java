package com.squareup.moshi;

import datadog.trace.api.Config;
import okio.BufferedSink;

import javax.annotation.Nullable;
import java.io.IOException;

public class TruncatedWriter extends JsonWriter{

  private static final int VALUE_MAX_LENGTH = Config.get().getIastTruncationMaxValueLength();

  private static final String TRUNCATED = "truncated";
  private static final String RIGHT = "right";

  private final JsonWriter delegated;

  public TruncatedWriter(JsonWriter writer){
    this.delegated = writer;
  }

  @Override
  public JsonWriter beginArray() throws IOException {
    return delegated.beginArray();
  }

  @Override
  public JsonWriter endArray() throws IOException {
    return delegated.endArray();
  }

  @Override
  public JsonWriter beginObject() throws IOException {
    return delegated.beginObject();
  }

  @Override
  public JsonWriter endObject() throws IOException {
    return delegated.endObject();
  }

  @Override
  public JsonWriter name(String name) throws IOException {
    return delegated.name(name);
  }

  @Override
  public JsonWriter value(@Nullable String value) throws IOException {
    if(value != null && value.length() > VALUE_MAX_LENGTH){
      delegated.value(value.substring(0, VALUE_MAX_LENGTH));
      delegated.name(TRUNCATED);
      return delegated.value(RIGHT);
    }
    return delegated.value(value);
  }

  @Override
  public JsonWriter nullValue() throws IOException {
    return delegated.nullValue();
  }

  @Override
  public JsonWriter value(boolean value) throws IOException {
    return delegated.value(value);
  }

  @Override
  public JsonWriter value(@Nullable Boolean value) throws IOException {
    return delegated.value(value);
  }

  @Override
  public JsonWriter value(double value) throws IOException {
    return delegated.value(value);
  }

  @Override
  public JsonWriter value(long value) throws IOException {
    return delegated.value(value);
  }

  @Override
  public JsonWriter value(@Nullable Number value) throws IOException {
    return delegated.value(value);
  }

  @Override
  public BufferedSink valueSink() throws IOException {
    return delegated.valueSink();
  }

  @Override
  public void close() throws IOException {
delegated.close();
  }

  @Override
  public void flush() throws IOException {
delegated.flush();
  }
}
