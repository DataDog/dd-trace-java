package dd.trace.instrumentation.springwebflux.client

import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class SpringWebfluxHttpClientBasicTest extends SpringWebfluxHttpClientBase {

  @Override
  WebClient createClient(CharSequence component) {
    return WebClient.builder().build()
  }

  @Override
  void check() {
  }
}
