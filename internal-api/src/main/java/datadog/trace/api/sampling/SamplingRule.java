package datadog.trace.api.sampling;

import java.util.Map;

/** This interface describes the criteria for a sampling rule. */
public interface SamplingRule {
  static String normalizeGlob(String name) {
    return name == null || MATCH_ALL.equals(name) ? MATCH_ALL : name;
  }

  /** The "match all" glob pattern . */
  String MATCH_ALL = "*";

  /**
   * Gets the glob pattern the span service must match to validate the rule.
   *
   * @return The glob pattern the span service must match.
   */
  String getService();

  /**
   * Gets the glob pattern the span name must match to validate the rule.
   *
   * @return The glob pattern the span name must match.
   */
  String getName();

  /**
   * Gets the glob pattern the span resource must match to validate the rule.
   *
   * @return The glob pattern the span resource must match.
   */
  String getResource();

  /**
   * Gets the collection of all (tag name, glob pattern) pairs that span tags must match to validate
   * the rules. If the collection is empty, the rule will be validated. If the collection has
   * entries, each key must exactly match a span tag name, and its associated glob pattern must
   * match the span tag value.
   *
   * @return The collection of all (tag name, glob pattern) pairs that span tags must match.
   */
  Map<String, String> getTags();

  /**
   * Gets the probability of keeping (sampling) the element matching the rule.
   *
   * @return The probability of sampling the element matching the rule, between {@code 0.0} and
   *     {@code 1.0}.
   */
  double getSampleRate();

  /** This interface describes the criteria of a sampling rule that can match against a trace. */
  interface TraceSamplingRule extends SamplingRule {}

  /** This interface describes the criteria of a sampling rule that can match against a span. */
  interface SpanSamplingRule extends SamplingRule {
    /**
     * Gets the limit applied to the rule, using a token bucket limiter.
     *
     * @return The limit applied to the rule, {@link Integer#MAX_VALUE} if no limit.
     */
    int getMaxPerSecond();
  }
}
