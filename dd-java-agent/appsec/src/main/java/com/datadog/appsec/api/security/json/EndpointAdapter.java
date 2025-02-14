package com.datadog.appsec.api.security.json;

import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.ToJson;
import datadog.trace.api.appsec.api.security.model.Endpoint;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EndpointAdapter {

  @ToJson
  public void toJson(@Nonnull final JsonWriter jsonWriter, @Nullable final Endpoint endpoint)
      throws IOException {
    if (endpoint == null) {
      jsonWriter.nullValue();
    } else {
      jsonWriter.beginObject();
      jsonWriter.name("type");
      jsonWriter.value(endpoint.getType().name());
      jsonWriter.name("method");
      jsonWriter.value(endpoint.getMethod().getName());
      jsonWriter.name("path");
      jsonWriter.value(endpoint.getPath());
      jsonWriter.name("operation-name");
      jsonWriter.value(endpoint.getOperation().getName());
      jsonWriter.name("request-body-type");
      jsonWriter.jsonValue(endpoint.getRequestBodyType());
      jsonWriter.name("response-body-type");
      jsonWriter.jsonValue(endpoint.getResponseBodyType());
      jsonWriter.name("response-code");
      jsonWriter.jsonValue(endpoint.getResponseCode());
      jsonWriter.name("authentication");
      jsonWriter.jsonValue(endpoint.getAuthentication());
      jsonWriter.name("metadata");
      jsonWriter.jsonValue(endpoint.getMetadata());
      jsonWriter.endObject();
    }
  }
}
