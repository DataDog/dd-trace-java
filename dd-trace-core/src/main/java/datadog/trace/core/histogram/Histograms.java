package datadog.trace.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.LogarithmicMapping;
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore;

public final class Histograms {
  private static final double gamma = 1.015625;
  private static final double offset = 1338.5;

  private static final LogarithmicMapping INDEX_MAPPING = new LogarithmicMapping(gamma, offset);

  public static DDSketch newHistogram() {
    return new DDSketch(INDEX_MAPPING, () -> new CollapsingLowestDenseStore(1024));
  }
}
