package datadog.trace.common.sampling

import datadog.trace.core.CoreSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Subject

class AllSamplerTest extends DDSpecification {

  @Subject
  CoreSpan span = Mock()

  private final AllSampler sampler = new AllSampler()

  def "test AllSampler"() {
    expect:
    for (int i = 0; i < 500; i++) {
      assert sampler.sample(span)
    }
  }
}
