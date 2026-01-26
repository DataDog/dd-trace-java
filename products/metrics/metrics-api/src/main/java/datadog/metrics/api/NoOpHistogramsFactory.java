package datadog.metrics.api;

final class NoOpHistogramsFactory implements Histograms.Factory {
  @Override
  public Histogram newHistogram() {
    return NoOpHistogram.INSTANCE;
  }

  @Override
  public Histogram newLogHistogram() {
    return NoOpHistogram.INSTANCE;
  }

  @Override
  public Histogram newHistogram(double relativeAccuracy, int maxNumBins) {
    return NoOpHistogram.INSTANCE;
  }
}
