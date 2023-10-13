package com.datadog.appsec.report;

import java.util.Map;
import java.util.Objects;

public class Rule {
  /**
   * The unique identifier of the rule that triggered the event. For example, ``ua-910-xax``.
   * (Required)
   */
  @com.squareup.moshi.Json(name = "id")
  private java.lang.String id;
  /** The friendly name of the rule that triggered the event. (Required) */
  @com.squareup.moshi.Json(name = "name")
  private java.lang.String name;
  /** (Required) */
  @com.squareup.moshi.Json(name = "tags")
  private Map<String, String> tags;

  /**
   * The unique identifier of the rule that triggered the event. For example, ``ua-910-xax``.
   * (Required)
   */
  public java.lang.String getId() {
    return id;
  }

  /**
   * The unique identifier of the rule that triggered the event. For example, ``ua-910-xax``.
   * (Required)
   */
  public void setId(java.lang.String id) {
    this.id = id;
  }

  /** The friendly name of the rule that triggered the event. (Required) */
  public java.lang.String getName() {
    return name;
  }

  /** The friendly name of the rule that triggered the event. (Required) */
  public void setName(java.lang.String name) {
    this.name = name;
  }

  /** (Required) */
  public Map<String, String> getTags() {
    return tags;
  }

  /** (Required) */
  public void setTags(Map<String, String> tags) {
    this.tags = tags;
  }

  @Override
  public java.lang.String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(Rule.class.getName())
        .append('@')
        .append(Integer.toHexString(System.identityHashCode(this)))
        .append('[');
    sb.append("id");
    sb.append('=');
    sb.append(((this.id == null) ? "<null>" : this.id));
    sb.append(',');
    sb.append("name");
    sb.append('=');
    sb.append(((this.name == null) ? "<null>" : this.name));
    sb.append(',');
    sb.append("tags");
    sb.append('=');
    sb.append(((this.tags == null) ? "<null>" : this.tags));
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
    result = ((result * 31) + ((this.name == null) ? 0 : this.name.hashCode()));
    result = ((result * 31) + ((this.id == null) ? 0 : this.id.hashCode()));
    result = ((result * 31) + ((this.tags == null) ? 0 : this.tags.hashCode()));
    return result;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof Rule)) {
      return false;
    }
    Rule rhs = ((Rule) other);
    return (((Objects.equals(this.name, rhs.name)) && (Objects.equals(this.id, rhs.id)))
        && (Objects.equals(this.tags, rhs.tags)));
  }

  public static class Builder {

    protected Rule instance;

    public Builder() {
      this.instance = new Rule();
    }

    public Rule build() {
      Rule result;
      result = this.instance;
      this.instance = null;
      return result;
    }

    public Builder withId(java.lang.String id) {
      this.instance.id = id;
      return this;
    }

    public Builder withName(java.lang.String name) {
      this.instance.name = name;
      return this;
    }

    public Builder withTags(Map<String, String> tags) {
      this.instance.tags = tags;
      return this;
    }
  }
}
