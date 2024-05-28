package datadog.trace.api.iast.telemetry;

import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityTypes;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
  TAINTED_FLAT_MODE("tainted.flat.mode", false, Scope.GLOBAL, Verbosity.INFORMATION),
  JSON_TAG_SIZE_EXCEED("json.tag.size.exceeded", true, Scope.GLOBAL, Verbosity.INFORMATION);

  private static final int COUNT;

  public static final String TRACE_METRIC_PREFIX = "_dd.iast.telemetry.";

  static {
    int count = 0;
    for (final IastMetric metric : values()) {
      metric.index = count;
      if (metric.tag == null) {
        count++;
      } else {
        count += metric.tag.count();
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
  private final String[] spanTags;

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
    spanTags = computeSpanTags(name, tag);
  }

  private String[] computeSpanTags(final String name, final Tag tag) {
    final String[] result = new String[tag == null ? 1 : tag.count()];
    if (tag == null) {
      result[0] = TRACE_METRIC_PREFIX + name;
    } else {
      for (int i = 0; i < result.length; i++) {
        final String spanTagValue = tag.values[i].toLowerCase(Locale.ROOT).replace('.', '_');
        result[i] = TRACE_METRIC_PREFIX + name + '.' + spanTagValue;
      }
    }
    return result;
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
   * java.util.concurrent.atomic.AtomicLongArray} of {@link IastMetricCollector}
   */
  public int getIndex(final byte tagValue) {
    if (tag == null) {
      return index;
    }
    if (tagValue < 0) {
      return -1;
    }
    return index + tagValue;
  }

  /** Gets the full value of the tag for the telemetry intake (name : value). */
  public String getTelemetryTag(final byte tagValue) {
    if (tag == null) {
      return null;
    }
    return tag.getTelemetryTag(tagValue);
  }

  /** Gets the key of the tag to be used in spans */
  public String getSpanTag(final byte tagValue) {
    if (tag == null) {
      return spanTags[0];
    }
    return spanTags[tagValue];
  }

  public abstract static class Tag {

    public static final Tag VULNERABILITY_TYPE =
        new Tag("vulnerability_type", VulnerabilityTypes.STRINGS) {
          @Nullable
          @Override
          public byte[] unwrap(byte tagValue) {
            return VulnerabilityTypes.unwrap(tagValue);
          }
        };

    public static final Tag SOURCE_TYPE =
        new Tag("source_type", SourceTypes.STRINGS) {

          @Nullable
          @Override
          public byte[] unwrap(byte tagValue) {
            return SourceTypes.unwrap(tagValue);
          }
        };

    protected final String name;

    protected final String[] values;

    protected final String[] telemetryTags;

    private Tag(final String name, final String[] values) {
      this.name = name;
      this.values = values;
      telemetryTags = new String[values.length];
      for (int i = 0; i < values.length; i++) {
        telemetryTags[i] = name + ":" + values[i];
      }
    }

    public String getName() {
      return name;
    }

    public int count() {
      return values.length;
    }

    @Nullable
    public abstract byte[] unwrap(final byte tagValue);

    public String getTelemetryTag(final byte tagValue) {
      return telemetryTags[tagValue];
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
