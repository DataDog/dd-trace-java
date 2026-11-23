package datadog.metrics.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HistogramsTest {

  @AfterEach
  void resetFactory() {
    Histograms.factory = Histograms.NO_OP;
  }

  @Test
  void constructorIsUsable() {
    assertNotNull(new Histograms());
  }

  @Test
  void registerIgnoresNull() {
    Histograms.Factory before = Histograms.factory;
    Histograms.register(null);
    assertSame(before, Histograms.factory);
  }

  @Test
  void registerReplacesFactory() {
    Histograms.Factory factory = mock(Histograms.Factory.class);
    Histograms.register(factory);
    assertSame(factory, Histograms.factory);
  }

  @Test
  void staticFactoryMethodsDelegateToRegisteredFactory() {
    Histograms.Factory factory = mock(Histograms.Factory.class);
    Histogram histogram = mock(Histogram.class);
    Histogram logHistogram = mock(Histogram.class);
    Histogram histogramWithBins = mock(Histogram.class);
    HistogramWithSum histogramWithSum = mock(HistogramWithSum.class);
    HistogramWithSum histogramWithSumFromBoundaries = mock(HistogramWithSum.class);

    when(factory.newHistogram()).thenReturn(histogram);
    when(factory.newLogHistogram()).thenReturn(logHistogram);
    when(factory.newHistogram(0.01, 10)).thenReturn(histogramWithBins);
    when(factory.newHistogramWithSum(0.01, 10)).thenReturn(histogramWithSum);
    List<Double> boundaries = Arrays.asList(1.0, 2.0);
    when(factory.newHistogramWithSum(boundaries)).thenReturn(histogramWithSumFromBoundaries);

    Histograms.register(factory);

    assertSame(histogram, Histogram.newHistogram());
    assertSame(logHistogram, Histogram.newLogHistogram());
    assertSame(histogramWithBins, Histogram.newHistogram(0.01, 10));
    assertSame(histogramWithSum, Histogram.newHistogramWithSum(0.01, 10));
    assertSame(histogramWithSumFromBoundaries, Histogram.newHistogramWithSum(boundaries));
  }
}
