package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.iast.VulnerabilityTypes.RESPONSE_HEADER;
import static datadog.trace.api.iast.VulnerabilityTypes.RESPONSE_HEADER_TYPES;

import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityTypes;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

public enum IastMetric {
  INSTRUMENTED_PROPAGATION("instrumented.propagation", true, Scope.GLOBAL, Verbosity.MANDATORY),
  INSTRUMENTED_SOURCE(
      "instrumented.source", true, Scope.GLOBAL, Tag.SOURCE_TYPE, Verbosity.MANDATORY),
  INSTRUMENTED_SINK(
      "instrumented.sink", true, Scope.GLOBAL, Tag.VULNERABILITY_TYPE, Verbosity.MANDATORY),
  EXECUTED_PROPAGATION("executed.propagation", true, Scope.REQUEST, Verbosity.DEBUG),
  EXECUTED_SOURCE("executed.source", true, Scope.REQUEST, Tag.SOURCE_TYPE, Verbosity.INFORMATION),
  EXECUTED_SINK(
      "executed.sink", true, Scope.REQUEST, Tag.VULNERABILITY_TYPE, Verbosity.INFORMATION),
  EXECUTED_TAINTED("executed.tainted", true, Scope.REQUEST, Verbosity.DEBUG),
  REQUEST_TAINTED("request.tainted", true, Scope.REQUEST, Verbosity.INFORMATION),
  TAINTED_FLAT_MODE("tainted.flat.mode", false, Scope.REQUEST, Verbosity.INFORMATION);

  private static final int COUNT;

  static {
    int count = 0;
    for (final IastMetric metric : values()) {
      if (metric.tag == null) {
        metric.index = count++;
      } else {
        metric.tagIndexes = new HashMap<>(metric.tag.getValues().length);
        for (final String tagValue : metric.tag.getValues()) {
          metric.tagIndexes.put(tagValue, count++);
        }
      }
    }
    COUNT = count;
  }

  public static int count() {
    return COUNT;
  }

  private final String name;
  private final boolean common;
  private final Scope scope;
  private final Tag tag;
  private final Verbosity verbosity;
  private int index;
  private Map<String, Integer> tagIndexes;

  IastMetric(
      final String name, final boolean common, final Scope scope, final Verbosity verbosity) {
    this(name, common, scope, null, verbosity);
  }

  IastMetric(
      final String name,
      final boolean common,
      final Scope scope,
      final Tag tag,
      final Verbosity verbosity) {
    this.name = name;
    this.common = common;
    this.scope = scope;
    this.tag = tag;
    this.verbosity = verbosity;
  }

  public String getName() {
    return name;
  }

  public boolean isCommon() {
    return common;
  }

  public Scope getScope() {
    return scope;
  }

  public boolean isEnabled(@Nonnull final Verbosity verbosity) {
    return verbosity.isEnabled(this.verbosity);
  }

  public Tag getTag() {
    return tag;
  }

  /**
   * Returns the index for the particular tag to be used in the {@link
   * java.util.concurrent.atomic.AtomicLongArray} of {@link IastMetricCollector}, returns -1 if the
   * tag is not found
   */
  public int getIndex(final String value) {
    if (tag == null) {
      return index;
    }
    return tagIndexes.getOrDefault(value, -1);
  }

  public static class Tag {

    private static final String[] EMPTY = new String[0];

    public static final Tag VULNERABILITY_TYPE =
        new Tag("vulnerability_type", VulnerabilityTypes.values()) {
          public String[] parse(final String tagValue) {
            if (RESPONSE_HEADER.equals(tagValue)) {
              return RESPONSE_HEADER_TYPES;
            }
            return EMPTY;
          }
        };

    public static final Tag SOURCE_TYPE = new Tag("source_type", SourceTypes.values());

    private final String name;
    private final String[] values;

    private Tag(final String name, final String... values) {
      this.name = name;
      this.values = values;
    }

    public String getName() {
      return name;
    }

    public String[] getValues() {
      return values;
    }

    public String[] parse(final String tagValue) {
      return EMPTY;
    }
  }

  public static final class Scope {

    public static final Scope GLOBAL = new Scope("global");
    public static final Scope REQUEST = new Scope("request");

    private final String name;

    private Scope(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }
}
