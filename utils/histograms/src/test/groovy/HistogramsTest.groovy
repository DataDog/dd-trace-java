import com.datadoghq.sketch.ddsketch.proto.DDSketch
import datadog.trace.core.histogram.DDSketchHistogram
import datadog.trace.core.histogram.Histogram
import datadog.trace.core.histogram.HistogramFactory
import datadog.trace.core.histogram.Histograms
import datadog.trace.core.histogram.StubHistogram
import datadog.trace.test.util.DDSpecification

import java.nio.ByteBuffer

class HistogramsTest extends DDSpecification {

  def "histogram factory creates DDSketch"() {
    expect:
    Histograms.newHistogramFactory().newHistogram() instanceof DDSketchHistogram
  }

  def "test serialize"() {
    setup:
    Histogram histogram = Histograms.newHistogramFactory().newHistogram()
    when:
    histogram.accept(42)
    ByteBuffer serialized = histogram.serialize()
    DDSketch proto = DDSketch.parseFrom(serialized)
    then:
    null != proto
  }

  def "stub histogram creates empty bytes"() {
    setup:
    HistogramFactory histogramFactory = new StubHistogram()
    when:
    Histogram histogram = histogramFactory.newHistogram()
    histogram.accept(42)
    then:
    histogram.serialize().capacity() == 0
  }

  def "load stub"() {
    setup:
    Histograms histograms = new Histograms(true)
    expect:
    histograms.newFactory().newHistogram() instanceof StubHistogram
  }

  def "fall back to stub if class can't be loaded"() {
    expect:
    Histograms.load("oops, not a class") instanceof StubHistogram
  }

  def "test max ddsketch"() {
    // this test only exists because we need to wrap DDSketch
    // - it is already well tested in the library
    expect:
    def histogram = Histograms.newHistogramFactory().newHistogram()
    histogram.accept(10)
    histogram.max() < 10.1
    histogram.max() >= 10.0
  }

  def "test max empty ddsketch"() {
    expect:
    def histogram = Histograms.newHistogramFactory().newHistogram()
    histogram.max() == 0
  }

  def "test p99 ddsketch"() {
    // this test only exists because we need to wrap DDSketch
    // - it is already well tested in the library
    expect:
    def histogram = Histograms.newHistogramFactory().newHistogram()
    for (int i = 0; i < 100; ++i) {
      histogram.accept(i)
    }
    assert Math.abs(histogram.valueAtQuantile(0.99) - 99) < 2
  }

  def "test p99 empty ddsketch"() {
    expect:
    def histogram = Histograms.newHistogramFactory().newHistogram()
    assert histogram.valueAtQuantile(0.99) == 0
  }

  def "test max stub"() {
    expect:
    def histogram = new StubHistogram()
    histogram.accept(10)
    histogram.max() == 0
  }

  def "test p99 stub"() {
    expect:
    def histogram = new StubHistogram()
    histogram.accept(10)
    histogram.valueAtQuantile(0.99) == 0
  }
}
