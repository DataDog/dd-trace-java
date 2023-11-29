package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.iast.VulnerabilityTypes.RESPONSE_HEADER;
import static datadog.trace.api.iast.VulnerabilityTypes.RESPONSE_HEADER_TYPES;
import static datadog.trace.api.iast.VulnerabilityTypes.SPRING_RESPONSE;
import static datadog.trace.api.iast.VulnerabilityTypes.SPRING_RESPONSE_TYPES;

import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityTypes;
import java.util.function.Function;
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
      metric.index = count;
      if (metric.tag == null) {
        count++;
      } else {
        count += metric.tag.getValues().length;
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

  public static class Tag {

    private static final byte[] EMPTY = new byte[0];

    public static final Tag VULNERABILITY_TYPE =
        new Tag("vulnerability_type", VulnerabilityTypes.values(), VulnerabilityTypes::toString) {

          @Override
          public boolean isWrapped(byte tagValue) {
            switch (tagValue) {
              case RESPONSE_HEADER:
              case SPRING_RESPONSE:
                return true;
              default:
                return false;
            }
          }

          public byte[] unwrap(final byte tagValue) {
            switch (tagValue) {
              case RESPONSE_HEADER:
                return RESPONSE_HEADER_TYPES;
              case SPRING_RESPONSE:
                return SPRING_RESPONSE_TYPES;
              default:
                return EMPTY;
            }
          }
        };

    public static final Tag SOURCE_TYPE =
        new Tag("source_type", SourceTypes.values(), SourceTypes::toString);

    private final String name;

    private final Function<Byte, String> toString;
    private final byte[] values;

    private Tag(final String name, final byte[] values, final Function<Byte, String> toString) {
      this.name = name;
      this.toString = toString;
      this.values = values;
    }

    public String getName() {
      return name;
    }

    public byte[] getValues() {
      return values;
    }

    /** Wrapped tags are aggregation of other tags that should be incremented together */
    public boolean isWrapped(final byte tagValue) {
      return false;
    }

    public byte[] unwrap(final byte tagValue) {
      return EMPTY;
    }

    public String toString(final byte tagValue) {
      return toString.apply(tagValue);
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
