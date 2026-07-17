package com.datadog.featureflag;

import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.Okio;

final class JsonApiUfcResponseParser {

  private static final String UNIVERSAL_FLAG_CONFIGURATION_TYPE = "universal-flag-configuration";
  private static final JsonReader.Options RESPONSE_FIELDS = JsonReader.Options.of("data");
  private static final JsonReader.Options DATA_FIELDS = JsonReader.Options.of("type", "attributes");

  static final JsonApiUfcResponseParser INSTANCE =
      new JsonApiUfcResponseParser(UniversalFlagConfigParser.INSTANCE);

  private final UniversalFlagConfigParser ufcParser;

  JsonApiUfcResponseParser(final UniversalFlagConfigParser ufcParser) {
    this.ufcParser = ufcParser;
  }

  @Nullable
  ServerConfiguration parse(final byte[] content) throws IOException {
    try (BufferedSource source = Okio.buffer(Okio.source(new ByteArrayInputStream(content)))) {
      final JsonReader reader = JsonReader.of(source);
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue();
        return null;
      }
      ServerConfiguration configuration = null;
      reader.beginObject();
      while (reader.hasNext()) {
        if (reader.selectName(RESPONSE_FIELDS) == 0) {
          configuration = parseData(reader);
        } else {
          reader.skipName();
          reader.skipValue();
        }
      }
      reader.endObject();
      requireEndOfDocument(reader);
      return configuration;
    }
  }

  @Nullable
  private ServerConfiguration parseData(final JsonReader reader) throws IOException {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue();
      return null;
    }
    String type = null;
    ServerConfiguration configuration = null;
    reader.beginObject();
    while (reader.hasNext()) {
      switch (reader.selectName(DATA_FIELDS)) {
        case 0:
          if (reader.peek() == JsonReader.Token.STRING) {
            type = reader.nextString();
          } else {
            reader.skipValue();
          }
          break;
        case 1:
          configuration = ufcParser.parse(reader);
          break;
        default:
          reader.skipName();
          reader.skipValue();
      }
    }
    reader.endObject();
    return UNIVERSAL_FLAG_CONFIGURATION_TYPE.equals(type)
        ? validConfiguration(configuration)
        : null;
  }

  @Nullable
  private static ServerConfiguration validConfiguration(
      @Nullable final ServerConfiguration configuration) {
    return configuration != null && configuration.flags != null ? configuration : null;
  }

  private static void requireEndOfDocument(final JsonReader reader) throws IOException {
    if (reader.peek() != JsonReader.Token.END_DOCUMENT) {
      throw new JsonDataException("JSON document was not fully consumed");
    }
  }
}
