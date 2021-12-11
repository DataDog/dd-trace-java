package datadog.trace.core.histogram;

public final class DDSketchHistogramFactory implements HistogramFactory {
  @Override
  public Histogram newHistogram() {
    return new DDSketchHistogram();
  }
}
