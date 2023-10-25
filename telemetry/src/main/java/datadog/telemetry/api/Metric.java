package datadog.telemetry.api;

import java.util.ArrayList;
import java.util.List;

public class Metric {
  private String namespace;
  private Boolean common;
  private String metric;
  private List<List<Number>> points = new ArrayList<>();
  private List<String> tags = new ArrayList<>();

  public enum TypeEnum {
    GAUGE("gauge"),

    @com.squareup.moshi.Json(name = "rate")
    RATE("rate"),

    @com.squareup.moshi.Json(name = "count")
    COUNT("count");

    final String value;

    TypeEnum(String v) {
      value = v;
    }

    public String value() {
      return value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  private TypeEnum type;

  public Boolean getCommon() {
    return common;
  }

  public void setCommon(boolean common) {
    common(common);
  }

  public Metric common(Boolean common) {
    this.common = common;
    return this;
  }

  public String getMetric() {
    return metric;
  }

  public void setMetric(String metric) {
    this.metric = metric;
  }

  public Metric metric(String metric) {
    this.metric = metric;
    return this;
  }

  public List<List<Number>> getPoints() {
    return points;
  }

  public void setPoints(List<List<Number>> points) {
    this.points = points;
  }

  public Metric points(List<List<Number>> points) {
    this.points = points;
    return this;
  }

  public Metric addPointsItem(List<Number> pointsItem) {
    this.points.add(pointsItem);
    return this;
  }

  public List<String> getTags() {
    return tags;
  }

  public void setTags(List<String> tags) {
    tags(tags);
  }

  public Metric tags(List<String> tags) {
    this.tags = tags;
    return this;
  }

  public TypeEnum getType() {
    return type;
  }

  public void setType(TypeEnum type) {
    type(type);
  }

  public Metric type(TypeEnum type) {
    this.type = type;
    return this;
  }

  public Metric namespace(final String namespace) {
    setNamespace(namespace);
    return this;
  }

  public void setNamespace(final String namespace) {
    this.namespace = namespace;
  }

  public String getNamespace() {
    return namespace;
  }
}
