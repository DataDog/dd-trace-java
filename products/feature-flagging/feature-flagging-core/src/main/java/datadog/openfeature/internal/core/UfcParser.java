package datadog.openfeature.internal.core;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses raw UFC documents and JSON API UFC responses without agent-owned model classes. */
public final class UfcParser {

  private static final String UFC_TYPE = "universal-flag-configuration";
  private static final JsonAdapter<Object> JSON_ADAPTER =
      new Moshi.Builder().build().adapter(Object.class);

  public ConfigurationSnapshot parse(final byte[] content) throws IOException {
    if (content == null || content.length == 0) {
      throw new IOException("UFC payload is empty");
    }

    final Object decoded;
    try {
      decoded = JSON_ADAPTER.fromJson(new String(content, StandardCharsets.UTF_8));
    } catch (final JsonDataException | IllegalArgumentException e) {
      throw new IOException("UFC payload is malformed", e);
    }

    final Map<String, Object> root = map(decoded, "UFC document");
    final Map<String, Object> configuration = unwrapJsonApi(root);
    final Map<String, Object> rawFlags = map(configuration.get("flags"), "flags");
    final Map<String, ConfigurationSnapshot.Flag> flags = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> entry : rawFlags.entrySet()) {
      try {
        flags.put(entry.getKey(), parseFlag(entry.getKey(), map(entry.getValue(), "flag")));
      } catch (final IOException | RuntimeException ignored) {
        // A malformed flag must not prevent other flags in the same UFC document from loading.
      }
    }

    final Map<String, Object> environment = optionalMap(configuration.get("environment"));
    return new ConfigurationSnapshot(
        optionalString(configuration.get("createdAt")),
        optionalString(configuration.get("format")),
        environment == null ? null : optionalString(environment.get("name")),
        flags);
  }

  private static Map<String, Object> unwrapJsonApi(final Map<String, Object> root)
      throws IOException {
    if (!root.containsKey("data")) {
      return root;
    }
    final Map<String, Object> data = map(root.get("data"), "data");
    if (!UFC_TYPE.equals(data.get("type"))) {
      throw new IOException("JSON API response does not contain UFC data");
    }
    return map(data.get("attributes"), "attributes");
  }

  private static ConfigurationSnapshot.Flag parseFlag(
      final String mapKey, final Map<String, Object> value) throws IOException {
    final String flagKey = defaultString(value.get("key"), mapKey);
    final boolean enabled = bool(value.get("enabled"), "enabled");
    final ConfigurationSnapshot.ValueType variationType =
        enumValue(
            ConfigurationSnapshot.ValueType.class,
            string(value.get("variationType"), "variationType"));

    final Map<String, Object> rawVariants = map(value.get("variations"), "variations");
    final Map<String, ConfigurationSnapshot.Variant> variants = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> entry : rawVariants.entrySet()) {
      final Map<String, Object> variant = map(entry.getValue(), "variant");
      variants.put(
          entry.getKey(),
          new ConfigurationSnapshot.Variant(
              defaultString(variant.get("key"), entry.getKey()), freeze(variant.get("value"))));
    }

    final List<Object> rawAllocations = optionalList(value.get("allocations"));
    final List<ConfigurationSnapshot.Allocation> allocations;
    if (rawAllocations == null) {
      allocations = null;
    } else {
      allocations = new ArrayList<>(rawAllocations.size());
      for (final Object rawAllocation : rawAllocations) {
        allocations.add(parseAllocation(map(rawAllocation, "allocation")));
      }
    }
    return new ConfigurationSnapshot.Flag(flagKey, enabled, variationType, variants, allocations);
  }

  private static ConfigurationSnapshot.Allocation parseAllocation(final Map<String, Object> value)
      throws IOException {
    return new ConfigurationSnapshot.Allocation(
        string(value.get("key"), "allocation key"),
        parseRules(optionalList(value.get("rules"))),
        parseDate(optionalString(value.get("startAt"))),
        parseDate(optionalString(value.get("endAt"))),
        parseSplits(optionalList(value.get("splits"))),
        Boolean.TRUE.equals(value.get("doLog")));
  }

  private static List<ConfigurationSnapshot.Rule> parseRules(final List<Object> values)
      throws IOException {
    if (values == null) {
      return null;
    }
    final List<ConfigurationSnapshot.Rule> rules = new ArrayList<>(values.size());
    for (final Object rawRule : values) {
      final Map<String, Object> rule = map(rawRule, "rule");
      final List<Object> rawConditions = optionalList(rule.get("conditions"));
      final List<ConfigurationSnapshot.Condition> conditions;
      if (rawConditions == null) {
        conditions = null;
      } else {
        conditions = new ArrayList<>(rawConditions.size());
        for (final Object rawCondition : rawConditions) {
          final Map<String, Object> condition = map(rawCondition, "condition");
          conditions.add(
              new ConfigurationSnapshot.Condition(
                  enumValue(
                      ConfigurationSnapshot.ConditionOperator.class,
                      string(condition.get("operator"), "condition operator")),
                  string(condition.get("attribute"), "condition attribute"),
                  freeze(condition.get("value"))));
        }
      }
      rules.add(new ConfigurationSnapshot.Rule(conditions));
    }
    return rules;
  }

  private static List<ConfigurationSnapshot.Split> parseSplits(final List<Object> values)
      throws IOException {
    if (values == null) {
      return null;
    }
    final List<ConfigurationSnapshot.Split> splits = new ArrayList<>(values.size());
    for (final Object rawSplit : values) {
      final Map<String, Object> split = map(rawSplit, "split");
      splits.add(
          new ConfigurationSnapshot.Split(
              parseShards(optionalList(split.get("shards"))),
              string(split.get("variationKey"), "variationKey"),
              stringMap(optionalMap(split.get("extraLogging"))),
              optionalInteger(split.get("serialId"))));
    }
    return splits;
  }

  private static List<ConfigurationSnapshot.Shard> parseShards(final List<Object> values)
      throws IOException {
    if (values == null) {
      return null;
    }
    final List<ConfigurationSnapshot.Shard> shards = new ArrayList<>(values.size());
    for (final Object rawShard : values) {
      final Map<String, Object> shard = map(rawShard, "shard");
      final List<Object> rawRanges = list(shard.get("ranges"), "ranges");
      final List<ConfigurationSnapshot.ShardRange> ranges = new ArrayList<>(rawRanges.size());
      for (final Object rawRange : rawRanges) {
        final Map<String, Object> range = map(rawRange, "range");
        ranges.add(
            new ConfigurationSnapshot.ShardRange(
                integer(range.get("start"), "range start"),
                integer(range.get("end"), "range end")));
      }
      shards.add(
          new ConfigurationSnapshot.Shard(
              string(shard.get("salt"), "shard salt"),
              ranges,
              integer(shard.get("totalShards"), "totalShards")));
    }
    return shards;
  }

  private static Long parseDate(final String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value).toEpochMilli();
    } catch (final DateTimeParseException ignored) {
      return null;
    }
  }

  private static Object freeze(final Object value) throws IOException {
    if (value instanceof Map) {
      final Map<String, Object> frozen = new LinkedHashMap<>();
      for (final Map.Entry<String, Object> entry : map(value, "object").entrySet()) {
        frozen.put(entry.getKey(), freeze(entry.getValue()));
      }
      return Collections.unmodifiableMap(frozen);
    }
    if (value instanceof List) {
      final List<Object> frozen = new ArrayList<>();
      for (final Object element : (List<?>) value) {
        frozen.add(freeze(element));
      }
      return Collections.unmodifiableList(frozen);
    }
    return value;
  }

  private static Map<String, String> stringMap(final Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    final Map<String, String> strings = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> entry : value.entrySet()) {
      strings.put(entry.getKey(), String.valueOf(entry.getValue()));
    }
    return strings;
  }

  private static <T extends Enum<T>> T enumValue(final Class<T> type, final String value) {
    return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
  }

  private static String defaultString(final Object value, final String defaultValue) {
    final String string = optionalString(value);
    return string == null ? defaultValue : string;
  }

  private static String string(final Object value, final String field) throws IOException {
    final String string = optionalString(value);
    if (string == null) {
      throw new IOException("Missing UFC " + field);
    }
    return string;
  }

  private static String optionalString(final Object value) {
    return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
  }

  private static boolean bool(final Object value, final String field) throws IOException {
    if (!(value instanceof Boolean)) {
      throw new IOException("Invalid UFC " + field);
    }
    return (Boolean) value;
  }

  private static int integer(final Object value, final String field) throws IOException {
    if (!(value instanceof Number)) {
      throw new IOException("Invalid UFC " + field);
    }
    return ((Number) value).intValue();
  }

  private static Integer optionalInteger(final Object value) throws IOException {
    return value == null ? null : integer(value, "integer");
  }

  private static List<Object> list(final Object value, final String field) throws IOException {
    final List<Object> list = optionalList(value);
    if (list == null) {
      throw new IOException("Invalid UFC " + field);
    }
    return list;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> optionalList(final Object value) throws IOException {
    if (value == null) {
      return null;
    }
    if (!(value instanceof List)) {
      throw new IOException("Expected UFC array");
    }
    return (List<Object>) value;
  }

  private static Map<String, Object> map(final Object value, final String field)
      throws IOException {
    final Map<String, Object> map = optionalMap(value);
    if (map == null) {
      throw new IOException("Invalid UFC " + field);
    }
    return map;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> optionalMap(final Object value) throws IOException {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map)) {
      throw new IOException("Expected UFC object");
    }
    for (final Object key : ((Map<?, ?>) value).keySet()) {
      if (!(key instanceof String)) {
        throw new IOException("UFC object key is not a string");
      }
    }
    return (Map<String, Object>) value;
  }
}
