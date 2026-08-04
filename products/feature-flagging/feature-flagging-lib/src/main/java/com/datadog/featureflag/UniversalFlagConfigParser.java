package com.datadog.featureflag;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.remoteconfig.ConfigurationDeserializer;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.Okio;

final class UniversalFlagConfigParser implements ConfigurationDeserializer<ServerConfiguration> {

  static final UniversalFlagConfigParser INSTANCE = new UniversalFlagConfigParser();

  private static final Moshi MOSHI =
      new Moshi.Builder()
          .add(Date.class, new DateAdapter())
          .add(FlagMapAdapter.FACTORY)
          .add(LenientBooleanAdapter.FACTORY)
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
        } catch (JsonDataException | IllegalArgumentException ignored) {
          // A malformed flag must not prevent other flags in the same config from evaluating.
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

  /**
   * Reads a boxed Boolean tolerantly: JSON null / wrong-typed values (string, number, object,
   * array) map to null rather than aborting the enclosing object's parse. Downstream reads must use
   * {@code Boolean.TRUE.equals(field)} so null resolves to the privacy-preserving default.
   *
   * <p>Only applies to {@code Boolean.class} (not primitive {@code boolean}), so mandatory
   * primitive-boolean fields (e.g. {@code Flag.enabled}) keep their strict parse.
   */
  static final class LenientBooleanAdapter extends JsonAdapter<Boolean> {

    static final Factory FACTORY =
        new Factory() {
          @Nullable
          @Override
          public JsonAdapter<?> create(
              @Nonnull final Type type,
              @Nonnull final Set<? extends Annotation> annotations,
              @Nonnull final Moshi moshi) {
            if (!annotations.isEmpty() || type != Boolean.class) {
              return null;
            }
            return new LenientBooleanAdapter();
          }
        };

    @Nullable
    @Override
    public Boolean fromJson(@Nonnull final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.BOOLEAN) {
        return reader.nextBoolean();
      }
      // null and every wrong-typed value collapse to null so the caller falls back to its default
      // rather than the enclosing config being rejected wholesale.
      reader.skipValue();
      return null;
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Boolean value)
        throws IOException {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }

  static final class DateAdapter extends JsonAdapter<Date> {

    @Nullable
    @Override
    public Date fromJson(@Nonnull final JsonReader reader) throws IOException {
      final String date = reader.nextString();
      if (date == null) {
        return null;
      }
      try {
        final Instant instant = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(date, Instant::from);
        return Date.from(instant);
      } catch (Exception e) {
        // ignore wrongly set dates
        return null;
      }
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Date value)
        throws IOException {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }
}
