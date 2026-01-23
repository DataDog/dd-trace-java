package datadog.metrics.api;

public interface Histograms {
  Histograms NO_OP = new NoOpHistograms();

  Histogram newHistogram();

  Histogram newLogHistogram();

  Histogram newHistogram(double relativeAccuracy, int maxNumBins);

  /**
   * Factory holder for the Histograms implementation. The implementation is registered at runtime
   * by metrics-lib.
   */
  final class Factory {
    private static volatile Histograms instance = NO_OP;

    /**
     * Register a Histograms implementation. This is called by metrics-lib to provide the DDSketch
     * implementation.
     */
    public static void register(Histograms histograms) {
      if (histograms != null) {
        instance = histograms;
      }
    }

    /**
     * Get the registered Histograms implementation. Returns NO_OP if no implementation has been
     * registered.
     */
    public static Histograms get() {
      return instance;
    }

    private Factory() {}
  }
}
