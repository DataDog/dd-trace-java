package dd.trace.instrumentation.springwebflux.client

import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
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
