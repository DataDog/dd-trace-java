package com.datadog.featureflag;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.FlagMap;
import datadog.trace.api.featureflag.ufc.v1.FlagValidator;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UniversalFlagConfigParser
    implements ConfigurationDeserializer<ServerConfiguration> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UniversalFlagConfigParser.class);

  public static final UniversalFlagConfigParser INSTANCE = new UniversalFlagConfigParser();

  private static final Moshi MOSHI =
      new Moshi.Builder()
          .add(Instant.class, new InstantAdapter())
          .add(AllocationAdapter.FACTORY)
          .add(FlagMapAdapter.FACTORY)
          .build();
  private static final JsonAdapter<ServerConfiguration> V1_ADAPTER =
      MOSHI.adapter(ServerConfiguration.class);

  private UniversalFlagConfigParser() {}

  @Override
  public ServerConfiguration deserialize(final byte[] content) throws IOException {
    try (BufferedSource source = Okio.buffer(Okio.source(new ByteArrayInputStream(content)))) {
      final JsonReader reader = JsonReader.of(source);
      final ServerConfiguration configuration = parse(reader);
      requireEndOfDocument(reader);
      return configuration;
    }
  }

  @Nullable
  ServerConfiguration parse(final JsonReader reader) throws IOException {
    return V1_ADAPTER.fromJson(reader);
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
          @Nullable
          @Override
          public JsonAdapter<?> create(
              @Nonnull final Type type,
              @Nonnull final Set<? extends Annotation> annotations,
              @Nonnull final Moshi moshi) {
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

    @Nullable
    @Override
    public Map<String, Flag> fromJson(@Nonnull final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      final FlagMap flags = new FlagMap();
      reader.beginObject();
      while (reader.hasNext()) {
        final String flagKey = reader.nextName();
        final Object rawFlag = reader.readJsonValue();
        try {
          FlagValidator.validateJson(rawFlag);
          final Flag flag = flagAdapter.fromJsonValue(rawFlag);
          if (flag != null) {
            FlagValidator.validate(flag);
            flags.put(flagKey, flag);
          } else {
            flags.reject(flagKey);
          }
        } catch (JsonDataException | IllegalArgumentException error) {
          flags.reject(flagKey);
          LOGGER.warn(
              "Dropping malformed FFE flag {} during remote config deserialization: {}",
              flagKey,
              error.toString());
        }
      }
      reader.endObject();
      return flags;
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Map<String, Flag> value)
        throws IOException {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }

  static final class InstantAdapter extends JsonAdapter<Instant> {

    @Nullable
    @Override
    public Instant fromJson(@Nonnull final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      return parseInstant(reader.nextString());
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Instant value)
        throws IOException {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }

  static final class AllocationAdapter extends JsonAdapter<Allocation> {

    static final Factory FACTORY =
        new Factory() {
          @Nullable
          @Override
          public JsonAdapter<?> create(
              @Nonnull final Type type,
              @Nonnull final Set<? extends Annotation> annotations,
              @Nonnull final Moshi moshi) {
            if (!annotations.isEmpty() || !Types.equals(type, Allocation.class)) {
              return null;
            }
            return new AllocationAdapter(moshi.adapter(AllocationJson.class));
          }
        };

    private final JsonAdapter<AllocationJson> delegate;

    AllocationAdapter(final JsonAdapter<AllocationJson> delegate) {
      this.delegate = delegate;
    }

    @Nullable
    @Override
    public Allocation fromJson(@Nonnull final JsonReader reader) throws IOException {
      final AllocationJson allocation = delegate.fromJson(reader);
      if (allocation == null) {
        return null;
      }
      return Allocation.fromInstants(
          allocation.key,
          allocation.rules,
          allocation.startAt,
          allocation.endAt,
          allocation.splits,
          allocation.doLog);
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Allocation value)
        throws IOException {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }

  static final class AllocationJson {
    String key;
    List<Rule> rules;
    Instant startAt;
    Instant endAt;
    List<Split> splits;
    Boolean doLog;
  }

  @Nullable
  private static Instant parseInstant(final String date) {
    try {
      return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(date, Instant::from);
    } catch (Exception e) {
      // ignore wrongly set dates
      return null;
    }
  }
}
