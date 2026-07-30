package datadog.openfeature.internal.core;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import okio.BufferedSource;
import okio.Okio;

/** Parses raw UFC documents and JSON API UFC responses into the existing UFC model. */
public final class UfcParser {

  private static final String UFC_TYPE = "universal-flag-configuration";
  private static final JsonReader.Options RESPONSE_FIELDS = JsonReader.Options.of("data");
  private static final JsonReader.Options DATA_FIELDS = JsonReader.Options.of("type", "attributes");
  private static final Moshi MOSHI =
      new Moshi.Builder().add(Date.class, new DateAdapter()).add(FlagMapAdapter.FACTORY).build();
  private static final JsonAdapter<ServerConfiguration> V1_ADAPTER =
      MOSHI.adapter(ServerConfiguration.class);

  public ServerConfiguration parse(final byte[] content) throws IOException {
    return parse(content, false);
  }

  public ServerConfiguration parseJsonApi(final byte[] content) throws IOException {
    return parse(content, true);
  }

  private static ServerConfiguration parse(
      final byte[] content, final boolean requireJsonApiEnvelope) throws IOException {
    if (content == null || content.length == 0) {
      throw new IOException("UFC payload is empty");
    }
    try (BufferedSource source = Okio.buffer(Okio.source(new ByteArrayInputStream(content)))) {
      final JsonReader reader = JsonReader.of(source);
      final boolean jsonApi = requireJsonApiEnvelope || isJsonApi(reader.peekJson());
      final ServerConfiguration configuration =
          jsonApi ? parseJsonApi(reader) : V1_ADAPTER.fromJson(reader);
      requireEndOfDocument(reader);
      if (jsonApi && (configuration == null || configuration.flags == null)) {
        throw new IOException("JSON API response does not contain UFC data");
      }
      return configuration;
    } catch (final JsonDataException | IllegalArgumentException e) {
      throw new IOException("UFC payload is malformed", e);
    }
  }

  private static boolean isJsonApi(final JsonReader reader) throws IOException {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      return false;
    }
    reader.beginObject();
    while (reader.hasNext()) {
      if (reader.selectName(RESPONSE_FIELDS) == 0) {
        return true;
      }
      reader.skipName();
      reader.skipValue();
    }
    return false;
  }

  private static ServerConfiguration parseJsonApi(final JsonReader reader) throws IOException {
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
    return configuration;
  }

  private static ServerConfiguration parseData(final JsonReader reader) throws IOException {
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
          configuration = V1_ADAPTER.fromJson(reader);
          break;
        default:
          reader.skipName();
          reader.skipValue();
      }
    }
    reader.endObject();
    return UFC_TYPE.equals(type) ? configuration : null;
  }

  private static void requireEndOfDocument(final JsonReader reader) throws IOException {
    // A strict JsonReader throws if another top-level value follows the parsed document.
    reader.peek();
  }

  static final class FlagMapAdapter extends JsonAdapter<Map<String, Flag>> {

    private static final Type FLAGS_TYPE =
        Types.newParameterizedType(Map.class, String.class, Flag.class);

    static final Factory FACTORY =
        new Factory() {
          @Override
          public JsonAdapter<?> create(
              final Type type, final Set<? extends Annotation> annotations, final Moshi moshi) {
            if (!annotations.isEmpty() || !Types.equals(type, FLAGS_TYPE)) {
              return null;
            }
            return new FlagMapAdapter(moshi.adapter(Flag.class));
          }
        };

    private final JsonAdapter<Flag> flagAdapter;

    FlagMapAdapter(final JsonAdapter<Flag> flagAdapter) {
      this.flagAdapter = flagAdapter;
    }

    @Override
    public Map<String, Flag> fromJson(final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      final Map<String, Flag> flags = new HashMap<>();
      reader.beginObject();
      while (reader.hasNext()) {
        final String flagKey = reader.nextName();
        final Object rawFlag = reader.readJsonValue();
        try {
          final Flag flag = flagAdapter.fromJsonValue(rawFlag);
          if (flag != null) {
            flags.put(flagKey, flag);
          }
        } catch (final JsonDataException | IllegalArgumentException ignored) {
          // A malformed flag must not prevent other flags in the same config from evaluating.
        }
      }
      reader.endObject();
      return flags;
    }

    @Override
    public void toJson(final JsonWriter writer, final Map<String, Flag> value) {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }

  static final class DateAdapter extends JsonAdapter<Date> {

    @Override
    public Date fromJson(final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      final String date = reader.nextString();
      try {
        final Instant instant = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(date, Instant::from);
        return Date.from(instant);
      } catch (final RuntimeException ignored) {
        return null;
      }
    }

    @Override
    public void toJson(final JsonWriter writer, final Date value) {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }
}
