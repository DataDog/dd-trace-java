package com.datadog.appsec.ddwaf;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WAFResultData {
  Rule rule;
  List<RuleMatch> rule_matches;
  String stack_id;

  // Forbidden addresses that contain sensitive data and must not be allowed
  private static final Set<String> FORBIDDEN_ADDRESSES = new HashSet<>();

  static {
    FORBIDDEN_ADDRESSES.add("usr.session_id");
    FORBIDDEN_ADDRESSES.add("server.request.cookies");
  }

  public static class RuleMatch {
    String operator;
    String operator_value;
    List<Parameter> parameters;
  }

  public static class Rule {
    public String id; // expose for log message
    String name;
    Map<String, String> tags;
    Output output; // optional output field for trace tagging support

    /**
     * Check if events should be generated for this rule. Backwards compatibility: if the output is
     * null or keep is null, we default to true.
     *
     * @return true if events should be generated, false otherwise
     */
    public boolean shouldGenerateEvents() {
      return output == null || output.event == null || output.event;
    }

    /**
     * Check if the trace should be kept for this rule. Backwards compatibility: if the output is
     * null or keep is null, we default to true.
     *
     * @return true if the trace should be kept, false otherwise
     */
    public boolean shouldKeepTrace() {
      return output == null || output.keep == null || output.keep;
    }

    /**
     * Get the attributes to be added to the trace.
     *
     * @return the attributes map, or null if no attributes
     */
    public Map<String, AttributeValue> getAttributes() {
      return output != null ? output.attributes : null;
    }
  }

  /** Represents the optional "output" field in a rule for trace tagging support. */
  public static class Output {
    private final Boolean keep;
    private final Boolean event;
    private final Map<String, AttributeValue> attributes;

    public Output(Boolean keep, Boolean event, Map<String, AttributeValue> attributes) {
      this.keep = keep;
      this.event = event;
      this.attributes = attributes;
    }

    /** Whether to keep the trace (set sampling priority). */
    public Boolean getKeep() {
      return keep;
    }

    /** Whether to generate events. */
    public Boolean getEvent() {
      return event;
    }

    /** Get the attributes to be added to the trace. */
    public Map<String, AttributeValue> getAttributes() {
      return attributes;
    }
  }

  /**
   * Represents an attribute value that can be either a literal value or extracted from request
   * data.
   */
  public static class AttributeValue {
    private final Object literalValue;
    private final String address;
    private final List<String> keyPath;
    private final List<String> transformers;

    /** Create a literal attribute value. */
    public static AttributeValue literal(Object value) {
      if (value != null && !isScalar(value)) {
        throw new IllegalArgumentException(
            "Literal values must be scalar (string, number, boolean)");
      }
      return new AttributeValue(value, null, null, null);
    }

    /** Create an attribute value extracted from request data. */
    public static AttributeValue fromRequestData(
        String address, List<String> keyPath, List<String> transformers) {
      if (address == null || address.trim().isEmpty()) {
        throw new IllegalArgumentException("Address cannot be null or empty");
      }

      // Check for forbidden addresses
      if (FORBIDDEN_ADDRESSES.contains(address)) {
        throw new IllegalArgumentException(
            "Address '" + address + "' is forbidden as it contains sensitive data");
      }

      return new AttributeValue(null, address, keyPath, transformers);
    }

    private AttributeValue(
        Object literalValue, String address, List<String> keyPath, List<String> transformers) {
      this.literalValue = literalValue;
      this.address = address;
      this.keyPath = keyPath;
      this.transformers = transformers;
    }

    /** Check if this is a literal value. */
    public boolean isLiteral() {
      return address == null;
    }

    /** Get the literal value (only valid if isLiteral() returns true). */
    public Object getLiteralValue() {
      return literalValue;
    }

    /** Get the address for request data extraction (only valid if isLiteral() returns false). */
    public String getAddress() {
      return address;
    }

    /** Get the key path for request data extraction (only valid if isLiteral() returns false). */
    public List<String> getKeyPath() {
      return keyPath;
    }

    /**
     * Get the transformers for request data extraction (only valid if isLiteral() returns false).
     */
    public List<String> getTransformers() {
      return transformers;
    }

    /** Check if a value is scalar (string, number, boolean). */
    private static boolean isScalar(Object value) {
      return value instanceof String
          || value instanceof Number
          || value instanceof Boolean
          || value instanceof Character;
    }

    @Override
    public String toString() {
      if (isLiteral()) {
        return "AttributeValue{literal=" + literalValue + "}";
      } else {
        return "AttributeValue{address='"
            + address
            + "', keyPath="
            + keyPath
            + ", transformers="
            + transformers
            + "}";
      }
    }
  }

  public static class Parameter extends MatchInfo {
    MatchInfo resource;
    MatchInfo params;
    MatchInfo db_type;
    List<String> highlight;
  }

  public static class MatchInfo {
    String address;
    List<Object> key_path;
    String value;
  }

  /**
   * New WAF result structure for trace tagging support. This replaces the old ddwaf_result
   * structure.
   */
  public static class TraceTaggingResult {
    public boolean timeout;
    public boolean keep;
    public long duration;
    public List<WAFResultData> events;
    public Map<String, Map<String, Object>> actions;
    public Map<String, Object> attributes;
  }

  /** Check if an address is forbidden. */
  public static boolean isForbiddenAddress(String address) {
    return FORBIDDEN_ADDRESSES.contains(address);
  }

  /** Get the set of forbidden addresses. */
  public static Set<String> getForbiddenAddresses() {
    return new HashSet<>(FORBIDDEN_ADDRESSES);
  }
}
