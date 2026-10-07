package com.datadog.openfeature.internal.ufc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses Universal Flag Configuration (UFC) documents.
 *
 * <p>The document is first read as a generic JSON tree (objects, arrays, strings, {@link Double}
 * numbers, booleans and nulls), then mapped onto the UFC model. Malformed flags are dropped
 * individually and reported through {@link ServerConfiguration#invalidFlags}; a malformed envelope
 * fails the whole document.
 */
public final class UniversalFlagConfigParser {
  private static final Logger LOGGER = LoggerFactory.getLogger(UniversalFlagConfigParser.class);

  static final String INVALID_FLAG = "invalid_flag";
  static final String INVALID_SEMVER_COMPARAND = "invalid_semver_comparand";
  private static final String UNIVERSAL_FLAG_CONFIGURATION_TYPE = "universal-flag-configuration";
  private static final long MAX_UNSIGNED_INT = 0xffff_ffffL;

  private static final JsonFactory JSON_FACTORY =
      new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private UniversalFlagConfigParser() {}

  /**
   * Parses a raw UFC document, as delivered by Remote Configuration.
   *
   * @param content the UFC JSON document.
   * @return the parsed configuration, or {@code null} for a JSON {@code null} document.
   * @throws IOException if the document is not valid JSON or does not match the UFC envelope.
   */
  @Nullable
  public static ServerConfiguration parse(final byte[] content) throws IOException {
    return toServerConfiguration(readDocument(content));
  }

  /**
   * Parses a JSON:API response wrapping a UFC document, as delivered by the CDN.
   *
   * @param content the JSON:API response body.
   * @return the parsed configuration, or {@code null} if the response holds no UFC document.
   * @throws IOException if the response is not valid JSON or does not match the UFC envelope.
   */
  @Nullable
  public static ServerConfiguration parseJsonApi(final byte[] content) throws IOException {
    final Object response = readDocument(content);
    if (!(response instanceof Map)) {
      return null;
    }
    final Object data = ((Map<?, ?>) response).get("data");
    if (!(data instanceof Map)) {
      return null;
    }
    final Map<?, ?> dataObject = (Map<?, ?>) data;
    if (!UNIVERSAL_FLAG_CONFIGURATION_TYPE.equals(dataObject.get("type"))) {
      return null;
    }
    final ServerConfiguration configuration = toServerConfiguration(dataObject.get("attributes"));
    return configuration != null && configuration.flags != null ? configuration : null;
  }

  private static Object readDocument(final byte[] content) throws IOException {
    try (JsonParser parser = JSON_FACTORY.createParser(content)) {
      if (parser.nextToken() == null) {
        throw new JsonParseException(parser, "Empty document");
      }
      final Object document = readValue(parser);
      if (parser.nextToken() != null) {
        throw new JsonParseException(parser, "Unexpected content after the document");
      }
      return document;
    }
  }

  private static Object readValue(final JsonParser parser) throws IOException {
    final JsonToken token = parser.currentToken();
    switch (token) {
      case START_OBJECT:
        final Map<String, Object> object = new LinkedHashMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
          final String name = parser.currentName();
          parser.nextToken();
          object.put(name, readValue(parser));
        }
        return object;
      case START_ARRAY:
        final List<Object> array = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          array.add(readValue(parser));
        }
        return array;
      case VALUE_STRING:
        return parser.getText();
      case VALUE_NUMBER_INT:
      case VALUE_NUMBER_FLOAT:
        return parser.getDoubleValue();
      case VALUE_TRUE:
        return Boolean.TRUE;
      case VALUE_FALSE:
        return Boolean.FALSE;
      case VALUE_NULL:
        return null;
      default:
        throw new JsonParseException(parser, "Unexpected token " + token);
    }
  }

  @Nullable
  private static ServerConfiguration toServerConfiguration(@Nullable final Object value)
      throws IOException {
    if (value == null) {
      return null;
    }
    try {
      final Map<?, ?> json = asObject(value, "configuration");
      final Map<String, String> invalidFlags = new HashMap<>();
      final ServerConfiguration configuration =
          new ServerConfiguration(
              asString(json.get("createdAt"), "createdAt"),
              asString(json.get("format"), "format"),
              lenientBoolean(json.get("observeFullEvaluationData")),
              toEnvironment(json.get("environment")),
              toFlags(json.get("flags"), invalidFlags));
      if (!invalidFlags.isEmpty()) {
        configuration.invalidFlags = invalidFlags;
      }
      return configuration;
    } catch (final IllegalArgumentException e) {
      throw new IOException("Invalid flag configuration: " + e.getMessage(), e);
    }
  }

  @Nullable
  private static Environment toEnvironment(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    return new Environment(asString(asObject(value, "environment").get("name"), "name"));
  }

  @Nullable
  private static Map<String, Flag> toFlags(
      @Nullable final Object value, final Map<String, String> invalidFlags) {
    if (value == null) {
      return null;
    }
    final Map<String, Flag> flags = new HashMap<>();
    for (final Map.Entry<?, ?> entry : asObject(value, "flags").entrySet()) {
      final String flagKey = (String) entry.getKey();
      try {
        final Flag flag = toFlag(entry.getValue());
        if (flag != null) {
          validateFlag(flagKey, flag);
          flags.put(flagKey, flag);
        }
      } catch (final IllegalArgumentException error) {
        invalidFlags.put(
            flagKey,
            error instanceof InvalidSemverComparandException
                ? INVALID_SEMVER_COMPARAND
                : INVALID_FLAG);
        LOGGER.warn("Dropping malformed FFE flag {}: {}", flagKey, error.toString());
      }
    }
    return flags;
  }

  @Nullable
  private static Flag toFlag(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<?, ?> json = asObject(value, "flag");
    return new Flag(
        asString(json.get("key"), "key"),
        strictBoolean(json.get("enabled"), "enabled"),
        asEnum(json.get("variationType"), ValueType.class, "variationType"),
        toVariants(json.get("variations")),
        toList(json.get("allocations"), "allocations", UniversalFlagConfigParser::toAllocation));
  }

  @Nullable
  private static Map<String, Variant> toVariants(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<String, Variant> variants = new HashMap<>();
    for (final Map.Entry<?, ?> entry : asObject(value, "variations").entrySet()) {
      variants.put((String) entry.getKey(), toVariant(entry.getValue()));
    }
    return variants;
  }

  @Nullable
  private static Variant toVariant(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<?, ?> json = asObject(value, "variant");
    return new Variant(asString(json.get("key"), "key"), json.get("value"));
  }

  @Nullable
  private static Allocation toAllocation(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<?, ?> json = asObject(value, "allocation");
    return new Allocation(
        asString(json.get("key"), "key"),
        toList(json.get("rules"), "rules", UniversalFlagConfigParser::toRule),
        toInstant(json.get("startAt")),
        toInstant(json.get("endAt")),
        toList(json.get("splits"), "splits", UniversalFlagConfigParser::toSplit),
        lenientBoolean(json.get("doLog")));
  }

  @Nullable
  private static Rule toRule(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<?, ?> json = asObject(value, "rule");
    return new Rule(
        toList(json.get("conditions"), "conditions", UniversalFlagConfigParser::toCondition));
  }

  @Nullable
  private static ConditionConfiguration toCondition(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<?, ?> json = asObject(value, "condition");
    return new ConditionConfiguration(
        asEnum(json.get("operator"), ConditionOperator.class, "operator"),
        asString(json.get("attribute"), "attribute"),
        json.get("value"));
  }

  @Nullable
  private static Split toSplit(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<?, ?> json = asObject(value, "split");
    final Object serialId = json.get("serialId");
    return new Split(
        toList(json.get("shards"), "shards", UniversalFlagConfigParser::toShard),
        asString(json.get("variationKey"), "variationKey"),
        toStringMap(json.get("extraLogging")),
        serialId == null ? null : asLenientInt(serialId, "serialId"));
  }

  @Nullable
  private static Shard toShard(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<?, ?> json = asObject(value, "shard");
    return new Shard(
        asString(json.get("salt"), "salt"),
        toList(json.get("ranges"), "ranges", UniversalFlagConfigParser::toShardRange),
        asUnsignedInt(json.get("totalShards"), "totalShards"));
  }

  @Nullable
  private static ShardRange toShardRange(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<?, ?> json = asObject(value, "range");
    return new ShardRange(
        asUnsignedInt(json.get("start"), "range start"),
        asUnsignedInt(json.get("end"), "range end"));
  }

  @Nullable
  private static Map<String, String> toStringMap(@Nullable final Object value) {
    if (value == null) {
      return null;
    }
    final Map<String, String> map = new HashMap<>();
    for (final Map.Entry<?, ?> entry : asObject(value, "extraLogging").entrySet()) {
      map.put((String) entry.getKey(), asString(entry.getValue(), (String) entry.getKey()));
    }
    return map;
  }

  @Nullable
  private static <T> List<T> toList(
      @Nullable final Object value, final String name, final ElementMapper<T> mapper) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof List)) {
      throw new IllegalArgumentException(name + " is not an array");
    }
    final List<?> json = (List<?>) value;
    final List<T> list = new ArrayList<>(json.size());
    for (final Object element : json) {
      list.add(mapper.map(element));
    }
    return list;
  }

  private static Map<?, ?> asObject(final Object value, final String name) {
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException(name + " is not an object");
    }
    return (Map<?, ?>) value;
  }

  @Nullable
  private static String asString(@Nullable final Object value, final String name) {
    if (value == null || value instanceof String) {
      return (String) value;
    }
    throw new IllegalArgumentException(name + " is not a string");
  }

  private static boolean strictBoolean(@Nullable final Object value, final String name) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    throw new IllegalArgumentException(name + " is not a boolean");
  }

  /** Maps wrong-typed values to {@code null} so callers fall back to their default. */
  @Nullable
  private static Boolean lenientBoolean(@Nullable final Object value) {
    return value instanceof Boolean ? (Boolean) value : null;
  }

  @Nullable
  private static <E extends Enum<E>> E asEnum(
      @Nullable final Object value, final Class<E> type, final String name) {
    final String constant = asString(value, name);
    if (constant == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, constant);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(name + " has an unknown value " + constant);
    }
  }

  /**
   * Coerces a number, or a numeric string, to an int, truncating fractions and saturating out of
   * range values.
   */
  private static int asLenientInt(final Object value, final String name) {
    if (value instanceof Double) {
      return (int) (double) (Double) value;
    }
    if (value instanceof String) {
      try {
        return (int) Double.parseDouble((String) value);
      } catch (final NumberFormatException e) {
        throw new IllegalArgumentException(name + " is not a number");
      }
    }
    throw new IllegalArgumentException(name + " is not a number");
  }

  private static long asUnsignedInt(@Nullable final Object value, final String name) {
    if (value == null) {
      return 0;
    }
    return asIntegral(value, 0, MAX_UNSIGNED_INT, name);
  }

  private static long asIntegral(
      final Object value, final long min, final long max, final String name) {
    if (!(value instanceof Double)) {
      throw new IllegalArgumentException(name + " is not a number");
    }
    final double number = (Double) value;
    if (number != Math.rint(number) || number < min || number > max) {
      throw new IllegalArgumentException("flag contains an invalid " + name + " value");
    }
    return (long) number;
  }

  /** Ignores wrongly set dates. */
  @Nullable
  private static Instant toInstant(@Nullable final Object value) {
    if (!(value instanceof String)) {
      return null;
    }
    try {
      return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse((String) value, Instant::from);
    } catch (final RuntimeException e) {
      return null;
    }
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
          throw new IllegalArgumentException(
              "flag \"" + flagKey + "\" contains a split with missing shards");
        }
        for (final Shard shard : split.shards) {
          if (shard == null || shard.totalShards == 0 || shard.ranges == null) {
            throw new IllegalArgumentException("flag \"" + flagKey + "\" contains invalid shards");
          }
          for (final ShardRange range : shard.ranges) {
            if (range == null) {
              throw new IllegalArgumentException(
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
              throw new IllegalArgumentException(
                  "flag \"" + flagKey + "\" has a non-numeric condition");
            }
            break;
          case ONE_OF:
          case NOT_ONE_OF:
            if (!(condition.value instanceof List)) {
              throw new IllegalArgumentException(
                  "flag \"" + flagKey + "\" has a non-list condition");
            }
            break;
          case IS_NULL:
            if (!(condition.value instanceof Boolean)) {
              throw new IllegalArgumentException(
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
   * Validates and caches SemVer comparands for all SEMVER_* conditions in a flag.
   *
   * @throws InvalidSemverComparandException if any condition has an invalid or non-string comparand
   *     value.
   */
  private static void validateAndCacheSemverComparands(final String flagKey, final Flag flag) {
    for (int allocIdx = 0; allocIdx < flag.allocations.size(); allocIdx++) {
      final Allocation allocation = flag.allocations.get(allocIdx);
      if (allocation == null || allocation.rules == null) {
        continue;
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

  @FunctionalInterface
  private interface ElementMapper<T> {
    @Nullable
    T map(@Nullable Object value);
  }

  /** Thrown when a SEMVER_* condition has an invalid or non-string comparand value. */
  static final class InvalidSemverComparandException extends IllegalArgumentException {
    InvalidSemverComparandException(final String message) {
      super(message);
    }
  }
}
