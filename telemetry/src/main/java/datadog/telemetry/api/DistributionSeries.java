package datadog.telemetry.api;

import java.util.ArrayList;
import java.util.List;

public class DistributionSeries {
  private String metric;
  private List<Integer> points = new ArrayList<>();
  private List<String> tags = new ArrayList<>();
  private Boolean common;
  private String namespace;

  public String getMetric() {
    return metric;
  }

  public DistributionSeries metric(String metric) {
    this.metric = metric;
    return this;
  }

  public List<Integer> getPoints() {
    return points;
  }

  public DistributionSeries points(List<Integer> points) {
    this.points = points;
    return this;
  }

  public void addPoint(int point) {
    points.add(point);
  }

  public List<String> getTags() {
    return tags;
  }

  public DistributionSeries tags(List<String> tags) {
    this.tags = tags;
    return this;
  }

  public Boolean getCommon() {
    return common;
  }

  public DistributionSeries common(Boolean common) {
    this.common = common;
    return this;
  }

  public String getNamespace() {
    return namespace;
  }

  public DistributionSeries namespace(String namespace) {
    this.namespace = namespace;
    return this;
  }
}
