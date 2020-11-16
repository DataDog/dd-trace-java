package datadog.trace.common.sampling

import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Subject

import java.util.regex.Pattern

class AllSamplerTest extends DDSpecification {

  @Subject
  DDSpan span = Mock()

  private final AllSampler sampler = new AllSampler()

  def "test AllSampler"() {
    expect:
    for (int i = 0; i < 500; i++) {
      assert sampler.doSample(span)
    }
  }

  def "test skip tag sampler"() {
    setup:
    sampler.addSkipTagPattern("http.url", Pattern.compile(".*/hello"))

    when:
    boolean sampled = sampler.sample(span)

    then:
    1 * span.getTag("http.url") >> "http://a/hello"

    expect:
    !sampled

    when:
    sampled = sampler.sample(span)

    then:
    span.getTag("http.url") >> "http://a/hello2"

    expect:
    sampled

  }
}
