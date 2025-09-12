package dd.trace.instrumentation.springwebflux.client

import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
class SpringWebfluxHttpClientFiltersRemovedTest extends SpringWebfluxHttpClientBase {

  @Override
  WebClient createClient(CharSequence component) {
    def builder = WebClient.builder()
    builder.filters({ filters -> filters.clear() })
    return builder.build()
  }

  @Override
  void check() {
  }
}
