package dd.trace.instrumentation.springwebflux.client

import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
class SpringWebfluxHttpClientBasicTest extends SpringWebfluxHttpClientBase {

  @Override
  WebClient createClient(CharSequence component) {
    return WebClient.builder().build()
  }

  @Override
  void check() {
  }
}
