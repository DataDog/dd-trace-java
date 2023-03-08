package datadog.telemetry.api;

import java.util.ArrayList;
import java.util.List;

public class Distributions extends Payload {

  @com.squareup.moshi.Json(name = "namespace")
  private String namespace;

  @com.squareup.moshi.Json(name = "series")
  private List<DistributionSeries> series = new ArrayList<>();

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

  public Distributions namespace(String namespace) {
    this.namespace = namespace;
    return this;
  }

  /**
   * Get series
   *
   * @return series
   */
  public List<DistributionSeries> getSeries() {
    return series;
  }

  /** Set series */
  public void setSeries(List<DistributionSeries> series) {
    this.series = series;
  }

  public Distributions series(List<DistributionSeries> series) {
    this.series = series;
    return this;
  }

  /** Add one series */
  public Distributions addDistributionSeries(DistributionSeries series) {
    this.series.add(series);
    return this;
  }

  /** Create a string representation of this pojo. */
  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("Distributions{");
    sb.append("namespace='").append(namespace).append('\'');
    sb.append(", series=").append(series);
    sb.append('}');
    return sb.toString();
  }
}
