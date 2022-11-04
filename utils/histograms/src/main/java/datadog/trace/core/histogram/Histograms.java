package datadog.trace.core.histogram;

import datadog.trace.api.Platform;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This indirection exists to make sure the class `DDSketch` can never be loaded on JDK7 */
@SuppressForbidden
public final class Histograms {

  private static final Logger log = LoggerFactory.getLogger(Histograms.class);

  private final boolean loadStub;

  private static final Histograms INSTANCE = new Histograms(!Platform.isJavaVersionAtLeast(8));

  public Histograms(boolean loadStub) {
    this.loadStub = loadStub;
  }

  HistogramFactory newFactory() {
    if (loadStub) {
      return new StubHistogram();
    }
    return new DDSketchHistogramFactory();
  }

  /** @return a histogram factory */
  public static HistogramFactory newHistogramFactory() {
    return INSTANCE.newFactory();
  }
}
