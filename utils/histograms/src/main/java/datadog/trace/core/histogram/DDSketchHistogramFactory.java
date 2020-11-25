package datadog.trace.core.histogram;

public class DDSketchHistogramFactory implements HistogramFactory {
  @Override
  public Histogram newHistogram() {
    return new DDSketchHistogram();
  }
}
