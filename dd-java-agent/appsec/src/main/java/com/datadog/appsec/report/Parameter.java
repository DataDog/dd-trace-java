package com.datadog.appsec.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Parameter {

  /**
   * The address containing the value that triggered the rule. For example ``http.server.query``.
   * (Required)
   */
  @com.squareup.moshi.Json(name = "address")
  private String address;

  /**
   * The path of the value that triggered the rule. For example ``["query", 0]`` to refer to the
   * value in ``{"query": ["triggering value"]}``. (Required)
   */
  @com.squareup.moshi.Json(name = "key_path")
  private List<Object> keyPath = new ArrayList<>();

  /** The value that triggered the rule. (Required) */
  @com.squareup.moshi.Json(name = "value")
  private String value;

  /** The part of the value that triggered the rule. (Required) */
  @com.squareup.moshi.Json(name = "highlight")
  private List<String> highlight = new ArrayList<>();

  /**
   * The address containing the value that triggered the rule. For example ``http.server.query``.
   * (Required)
   */
  public String getAddress() {
    return address;
  }

  /**
   * The address containing the value that triggered the rule. For example ``http.server.query``.
   * (Required)
   */
  public void setAddress(String address) {
    this.address = address;
  }

  /**
   * The path of the value that triggered the rule. For example ``["query", 0]`` to refer to the
   * value in ``{"query": ["triggering value"]}``. (Required)
   */
  public List<Object> getKeyPath() {
    return keyPath;
  }

  /** The value that triggered the rule. (Required) */
  public String getValue() {
    return value;
  }

  /** The part of the value that triggered the rule. (Required) */
  public List<String> getHighlight() {
    return highlight;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(Parameter.class.getName())
        .append('@')
        .append(Integer.toHexString(System.identityHashCode(this)))
        .append('[');
    sb.append("address");
    sb.append('=');
    sb.append(((this.address == null) ? "<null>" : this.address));
    sb.append(',');
    sb.append("keyPath");
    sb.append('=');
    sb.append(((this.keyPath == null) ? "<null>" : this.keyPath));
    sb.append(',');
    sb.append("value");
    sb.append('=');
    sb.append(((this.value == null) ? "<null>" : this.value));
    sb.append(',');
    sb.append("highlight");
    sb.append('=');
    sb.append(((this.highlight == null) ? "<null>" : this.highlight));
    sb.append(',');
    if (sb.charAt((sb.length() - 1)) == ',') {
      sb.setCharAt((sb.length() - 1), ']');
    } else {
      sb.append(']');
    }
    return sb.toString();
  }

  @Override
  public int hashCode() {
    int result = 1;
    result = ((result * 31) + ((this.highlight == null) ? 0 : this.highlight.hashCode()));
    result = ((result * 31) + ((this.address == null) ? 0 : this.address.hashCode()));
    result = ((result * 31) + ((this.value == null) ? 0 : this.value.hashCode()));
    result = ((result * 31) + ((this.keyPath == null) ? 0 : this.keyPath.hashCode()));
    return result;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof Parameter)) {
      return false;
    }

    Parameter rhs = ((Parameter) other);
    return (Objects.equals(this.highlight, rhs.highlight)
            || this.highlight != null && this.highlight.equals(rhs.highlight))
        && Objects.equals(this.address, rhs.address)
        && Objects.equals(this.value, rhs.value)
        && Objects.equals(this.keyPath, rhs.keyPath);
  }

  public static class Builder {

    protected Parameter instance;

    public Builder() {
      this.instance = new Parameter();
    }

    public Parameter build() {
      Parameter result;
      result = this.instance;
      this.instance = null;
      return result;
    }

    public Builder withAddress(String address) {
      this.instance.address = address;
      return this;
    }

    public Builder withKeyPath(List<Object> keyPath) {
      this.instance.keyPath = keyPath;
      return this;
    }

    public Builder withValue(String value) {
      this.instance.value = value;
      return this;
    }

    public Builder withHighlight(List<String> highlight) {
      this.instance.highlight = highlight;
      return this;
    }
  }
}
