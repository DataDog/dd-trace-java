import com.datadoghq.sketch.ddsketch.proto.DDSketch
import datadog.trace.core.histogram.DDSketchHistogram
import datadog.trace.core.histogram.Histogram
import datadog.trace.core.histogram.Histograms
import datadog.trace.test.util.DDSpecification

import java.nio.ByteBuffer

class HistogramsTest extends DDSpecification {

  def "histogram factory creates DDSketch"() {
    expect:
    Histograms.newHistogram() instanceof DDSketchHistogram
  }

  def "test serialize"() {
    setup:
    Histogram histogram = Histograms.newHistogram()
    when:
    histogram.accept(42)
    ByteBuffer serialized = histogram.serialize()
    DDSketch proto = DDSketch.parseFrom(serialized)
    then:
    null != proto
  }

  def "test max ddsketch"() {
    // this test only exists because we need to wrap DDSketch
    // - it is already well tested in the library
    expect:
    def histogram = Histograms.newHistogram()
    histogram.accept(10)
    histogram.max() < 10.1
    histogram.max() >= 10.0
  }

  def "test max empty ddsketch"() {
    expect:
    def histogram = Histograms.newHistogram()
    histogram.max() == 0
  }

  def "test p99 ddsketch"() {
    // this test only exists because we need to wrap DDSketch
    // - it is already well tested in the library
    expect:
    def histogram = Histograms.newHistogram()
    for (int i = 0; i < 100; ++i) {
      histogram.accept(i)
    }
    assert Math.abs(histogram.valueAtQuantile(0.99) - 99) < 2
  }

  def "test p99 empty ddsketch"() {
    expect:
    def histogram = Histograms.newHistogram()
    assert histogram.valueAtQuantile(0.99) == 0
  }
}
