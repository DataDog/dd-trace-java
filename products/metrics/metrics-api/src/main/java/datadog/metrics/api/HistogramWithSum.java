package datadog.metrics.api;

/** Adds exact summary statistics to the histogram */
public interface HistogramWithSum extends Histogram {
  double getSum();
}
