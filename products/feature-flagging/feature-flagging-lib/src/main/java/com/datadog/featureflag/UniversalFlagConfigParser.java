package com.datadog.featureflag;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ParsedSemver;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Shard;
import datadog.trace.api.featureflag.ufc.v1.ShardRange;
import datadog.trace.api.featureflag.ufc.v1.Split;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UniversalFlagConfigParser implements ConfigurationDeserializer<ServerConfiguration> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UniversalFlagConfigParser.class);

  static final String INVALID_FLAG = "invalid_flag";
  static final String INVALID_SEMVER_COMPARAND = "invalid_semver_comparand";
  private static final long MAX_UNSIGNED_INT = 0xffff_ffffL;

  /**
   * Side-channel for tracking flags that failed semver comparand validation during parsing. Cleared
   * and populated by {@link #parse(JsonReader)} so that {@link ServerConfiguration#invalidFlags}
   * can be set after the Moshi adapter returns.
   */
  static final ThreadLocal<Map<String, String>> INVALID_FLAGS_HOLDER =
      ThreadLocal.withInitial(HashMap::new);

  static final UniversalFlagConfigParser INSTANCE = new UniversalFlagConfigParser();

  private static final Moshi MOSHI =
      new Moshi.Builder()
          .add(Instant.class, new InstantAdapter())
          .add(ShardAdapter.FACTORY)
          .add(AllocationAdapter.FACTORY)
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
    INVALID_FLAGS_HOLDER.get().clear();
    final ServerConfiguration configuration = V1_ADAPTER.fromJson(reader);
    if (configuration != null) {
      final Map<String, String> invalid = new HashMap<>(INVALID_FLAGS_HOLDER.get());
      if (!invalid.isEmpty()) {
        configuration.invalidFlags = invalid;
      }
    }
    INVALID_FLAGS_HOLDER.get().clear();
    return configuration;
  }

  private static void requireEndOfDocument(final JsonReader reader) throws IOException {
    // A strict JsonReader throws if another top-level value follows the parsed document.
    reader.peek();
  }

  /** Converts a UFC uint32 wire value to its binary-compatible int representation. */
  private static int toUnsignedInt(@Nullable final Long value, final String fieldName) {
    if (value == null) {
      return 0;
    }
    if (value < 0 || value > MAX_UNSIGNED_INT) {
      throw new InvalidFlagException("flag contains an invalid " + fieldName + " value");
    }
    return value.intValue();
  }

  /** Validates the required nested UFC fields and SemVer comparands for a flag. */
  private static void validateFlag(final String flagKey, final Flag flag) {
    if (flag.allocations == null) {
      return;
    }
    for (final Allocation allocation : flag.allocations) {
      if (allocation == null) {
        continue;
      }
      validateConditionOperands(flagKey, allocation);
      if (allocation.splits == null) {
        continue;
      }
      for (final Split split : allocation.splits) {
        if (split == null) {
          continue;
        }
        if (split.shards == null) {
          throw new InvalidFlagException(
              "flag \"" + flagKey + "\" contains a split with missing shards");
        }
        for (final Shard shard : split.shards) {
          if (shard == null
              || Integer.toUnsignedLong(shard.totalShards) == 0
              || shard.ranges == null) {
            throw new InvalidFlagException("flag \"" + flagKey + "\" contains invalid shards");
          }
          for (final ShardRange range : shard.ranges) {
            if (range == null) {
              throw new InvalidFlagException(
                  "flag \"" + flagKey + "\" contains an invalid shard range");
            }
          }
        }
      }
    }
    validateAndCacheSemverComparands(flagKey, flag);
  }

  private static void validateConditionOperands(final String flagKey, final Allocation allocation) {
    if (allocation.rules == null) {
      return;
    }
    for (final Rule rule : allocation.rules) {
      if (rule == null || rule.conditions == null) {
        continue;
      }
      for (final ConditionConfiguration condition : rule.conditions) {
        if (condition == null || condition.operator == null) {
          continue;
        }
        switch (condition.operator) {
          case LT:
          case LTE:
          case GT:
          case GTE:
            if (!(condition.value instanceof Number)) {
              throw new InvalidFlagException(
                  "flag \"" + flagKey + "\" has a non-numeric condition");
            }
            break;
          case ONE_OF:
          case NOT_ONE_OF:
            if (!(condition.value instanceof List)) {
              throw new InvalidFlagException("flag \"" + flagKey + "\" has a non-list condition");
            }
            break;
          case IS_NULL:
            if (!(condition.value instanceof Boolean)) {
              throw new InvalidFlagException(
                  "flag \"" + flagKey + "\" has a non-boolean condition");
            }
            break;
          default:
            break;
        }
      }
    }
  }

  /**
   * Validates and caches SemVer comparands for all SEMVER_* conditions in a flag. Throws {@link
   * InvalidSemverComparandException} if any condition has an invalid or non-string comparand value.
   */
  private static void validateAndCacheSemverComparands(final String flagKey, final Flag flag) {
    if (flag.allocations == null) {
      return;
    }
    for (int allocIdx = 0; allocIdx < flag.allocations.size(); allocIdx++) {
      final Allocation allocation = flag.allocations.get(allocIdx);
      if (allocation.rules == null) {
        continue;
      }
      for (final Rule rule : allocation.rules) {
        if (rule.conditions == null) {
          continue;
        }
        for (final ConditionConfiguration condition : rule.conditions) {
          if (condition.operator == null) {
            continue;
          }
          switch (condition.operator) {
            case SEMVER_EQ:
            case SEMVER_NEQ:
            case SEMVER_LT:
            case SEMVER_LTE:
            case SEMVER_GT:
            case SEMVER_GTE:
              if (!(condition.value instanceof String)) {
                throw new InvalidSemverComparandException(
                    "flag \""
                        + flagKey
                        + "\" allocation "
                        + allocIdx
                        + " rule has condition with operator \""
                        + condition.operator
                        + "\" that requires string value");
              }
              final ParsedSemver parsed = ParsedSemver.parse((String) condition.value);
              if (parsed == null) {
                throw new InvalidSemverComparandException(
                    "flag \""
                        + flagKey
                        + "\" allocation "
                        + allocIdx
                        + " rule has condition with operator \""
                        + condition.operator
                        + "\" and invalid semantic version \""
                        + condition.value
                        + "\"");
              }
              condition.semverComparand = parsed;
              break;
            default:
              // Non-semver operators are not validated here
              break;
          }
        }
      }
    }
  }

  /** Thrown when a flag has an invalid UFC shape. */
  static final class InvalidFlagException extends IllegalArgumentException {
    InvalidFlagException(final String message) {
      super(message);
    }
  }

  /** Thrown when a SEMVER_* condition has an invalid or non-string comparand value. */
  static final class InvalidSemverComparandException extends IllegalArgumentException {
    InvalidSemverComparandException(final String message) {
      super(message);
    }
  }

  /**
   * Reads UFC uint32 shard fields through Long first, then preserves the bootstrap model's int ABI
   * by storing the accepted value as its two's-complement bit pattern.
   */
  static final class ShardAdapter extends JsonAdapter<Shard> {

    static final Factory FACTORY =
        new Factory() {
          @Nullable
          @Override
          public JsonAdapter<?> create(
              @Nonnull final Type type,
              @Nonnull final Set<? extends Annotation> annotations,
              @Nonnull final Moshi moshi) {
            if (!annotations.isEmpty() || type != Shard.class) {
              return null;
            }
            return new ShardAdapter(moshi.adapter(ShardJson.class));
          }
        };

    private final JsonAdapter<ShardJson> delegate;

    ShardAdapter(final JsonAdapter<ShardJson> delegate) {
      this.delegate = delegate;
    }

    @Nullable
    @Override
    public Shard fromJson(@Nonnull final JsonReader reader) throws IOException {
      final ShardJson shard = delegate.fromJson(reader);
      if (shard == null) {
        return null;
      }
      final List<ShardRange> ranges;
      if (shard.ranges == null) {
        ranges = null;
      } else {
        ranges = new ArrayList<>();
        for (final ShardRangeJson range : shard.ranges) {
          ranges.add(
              range == null
                  ? null
                  : new ShardRange(
                      toUnsignedInt(range.start, "range start"),
                      toUnsignedInt(range.end, "range end")));
        }
      }
      return new Shard(shard.salt, ranges, toUnsignedInt(shard.totalShards, "totalShards"));
    }

    @Override
    public void toJson(@Nonnull final JsonWriter writer, @Nullable final Shard value) {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }

  static final class ShardJson {
    String salt;
    List<ShardRangeJson> ranges;
    Long totalShards;
  }

  static final class ShardRangeJson {
    Long start;
    Long end;
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
            validateFlag(flagKey, flag);
            flags.put(flagKey, flag);
          }
        } catch (JsonDataException | IllegalArgumentException error) {
          INVALID_FLAGS_HOLDER.get().put(flagKey, INVALID_FLAG);
          if (error instanceof InvalidSemverComparandException) {
            INVALID_FLAGS_HOLDER.get().put(flagKey, INVALID_SEMVER_COMPARAND);
          }
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

  /**
   * Reads a boxed Boolean tolerantly: JSON null and wrong-typed values (string, number, object,
   * array) map to null rather than aborting the enclosing object's parse. Downstream reads must use
   * Boolean.TRUE.equals(field) so null resolves to the privacy-preserving default.
   *
   * <p>Only applies to Boolean.class (not primitive boolean), so mandatory primitive-boolean fields
   * (e.g. Flag.enabled) keep their strict parse.
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
