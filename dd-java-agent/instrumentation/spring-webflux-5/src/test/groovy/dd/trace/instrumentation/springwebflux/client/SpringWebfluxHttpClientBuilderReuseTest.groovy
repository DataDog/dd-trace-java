package dd.trace.instrumentation.springwebflux.client

import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class SpringWebfluxHttpClientBuilderReuseTest extends SpringWebfluxHttpClientBase {

  @Override
  WebClient createClient(CharSequence component) {
    def builder = WebClient.builder()
    builder.build()
    return builder.build()
  }

  @Override
  void check() {
  }
}
