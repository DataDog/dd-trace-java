package datadog.telemetry.api;

import java.util.ArrayList;
import java.util.List;

public class DistributionSeries {

  @com.squareup.moshi.Json(name = "metric")
  private String metric;

  @com.squareup.moshi.Json(name = "points")
  private List<Integer> points = new ArrayList<>();

  @com.squareup.moshi.Json(name = "tags")
  private List<String> tags = new ArrayList<>();

  @com.squareup.moshi.Json(name = "common")
  private Boolean common;

  @com.squareup.moshi.Json(name = "namespace")
  private String namespace;

  /**
   * Get metric
   *
   * @return metric
   */
  public String getMetric() {
    return metric;
  }

  /** Set metric */
  public void setMetric(String metric) {
    this.metric = metric;
  }

  public DistributionSeries metric(String metric) {
    this.metric = metric;
    return this;
  }

  /**
   * Get points
   *
   * @return points
   */
  public List<Integer> getPoints() {
    return points;
  }

  /** Set points */
  public void setPoints(List<Integer> points) {
    this.points = points;
  }

  public DistributionSeries points(List<Integer> points) {
    this.points = points;
    return this;
  }

  /**
   * Get tags
   *
   * @return tags
   */
  public List<String> getTags() {
    return tags;
  }

  /** Set tags */
  public void setTags(List<String> tags) {
    this.tags = tags;
  }

  public DistributionSeries tags(List<String> tags) {
    this.tags = tags;
    return this;
  }

  /**
   * Get common
   *
   * @return common
   */
  public Boolean getCommon() {
    return common;
  }

  /** Set common */
  public void setCommon(Boolean common) {
    this.common = common;
  }

  public DistributionSeries common(Boolean common) {
    this.common = common;
    return this;
  }

  /**
   * Get namespace
   *
   * @return namespace
   */
  public String getNamespace() {
    return namespace;
  }

  /** Set namespace */
  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public DistributionSeries namespace(String namespace) {
    this.namespace = namespace;
    return this;
  }

  /** Create a string representation of this pojo. */
  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("DistributionSeries{");
    sb.append("metric='").append(metric).append('\'');
    sb.append(", points=").append(points);
    sb.append(", tags=").append(tags);
    sb.append(", common=").append(common);
    sb.append(", namespace='").append(namespace).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
