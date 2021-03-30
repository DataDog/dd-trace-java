package datadog.trace.common.sampling

import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Subject

class AllSamplerTest extends DDSpecification {

  @Subject
  DDSpan span = Mock()

  private final AllSampler sampler = new AllSampler()

  def "test AllSampler"() {
    expect:
    for (int i = 0; i < 500; i++) {
      assert sampler.sample(span)
    }
  }
}
