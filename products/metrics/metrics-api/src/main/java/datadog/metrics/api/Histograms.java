package datadog.metrics.api;

public interface Histograms {
  Histograms NO_OP = new NoOpHistograms();

  Histogram newHistogram();

  Histogram newLogHistogram();

  Histogram newHistogram(double relativeAccuracy, int maxNumBins);
}
