package datadog.metrics.api;

import java.util.List;

public class Histograms {
  static final Factory NO_OP = new NoOpHistogramsFactory();
  static volatile Factory factory = NO_OP;

  /**
   * Register a Histograms implementation. This is called by metrics-lib to provide the DDSketch
   * implementation.
   */
  public static void register(Factory histograms) {
    if (histograms != null) {
      Histograms.factory = histograms;
    }
  }

  public interface Factory {
    Histogram newHistogram();

    Histogram newLogHistogram();

    Histogram newHistogram(double relativeAccuracy, int maxNumBins);

    Histogram newHistogram(List<Double> binBoundaries);
  }
}
