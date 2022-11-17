package datadog.trace.core.histogram;

public final class Histograms {
  public static Histogram newHistogram() {
    return new DDSketchHistogram();
  }
}
