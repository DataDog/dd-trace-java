package com.datadog.debugger.symbol;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.IOException;
import java.util.List;
import okio.BufferedSink;

/**
 * Top level payload of a SymDB upload. Scopes are held in their pre-serialized JSON form and
 * written verbatim to the output by {@link ServiceVersionAdapter}.
 */
public class ServiceVersion {
  private final String service;
  private final String env;
  private final String version;
  private final String language;
  private final List<String> scopes;
  private final String uploadId;
  private final long batchNum;
  private final boolean isFinal;

  public ServiceVersion(
      String service,
      String env,
      String version,
      String language,
      List<String> scopes,
      String uploadId,
      long batchNum,
      boolean isFinal) {
    this.service = service;
    this.env = env;
    this.version = version;
    this.language = language;
    this.scopes = scopes;
    this.uploadId = uploadId;
    this.batchNum = batchNum;
    this.isFinal = isFinal;
  }

  /** Returns the JSON-serialized scopes. */
  public List<String> getScopes() {
    return scopes;
  }

  /** Serialization-only adapter that appends pre-serialized scopes as raw JSON values. */
  public static class ServiceVersionAdapter extends JsonAdapter<ServiceVersion> {
    @Override
    public ServiceVersion fromJson(JsonReader reader) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void toJson(JsonWriter writer, ServiceVersion value) throws IOException {
      if (value == null) {
        writer.nullValue();
        return;
      }
      writer.beginObject();
      writer.name("service").value(value.service);
      writer.name("env").value(value.env);
      writer.name("version").value(value.version);
      writer.name("language").value(value.language);
      writer.name("scopes");
      if (value.scopes == null) {
        writer.nullValue();
      } else {
        writer.beginArray();
        for (String scope : value.scopes) {
          try (BufferedSink sink = writer.valueSink()) {
            sink.writeUtf8(scope);
          }
        }
        writer.endArray();
      }
      writer.name("upload_id").value(value.uploadId);
      writer.name("batch_num").value(value.batchNum);
      writer.name("final").value(value.isFinal);
      writer.endObject();
    }
  }
}
