package dd.trace.instrumentation.springwebflux6.client

import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
class SpringWebfluxHttpClientFilterTest extends SpringWebfluxHttpClientBase {

  CollectingFilter filter = null
  String component = ""

  @Override
  WebClient createClient(CharSequence component) {
    filter = new CollectingFilter()
    this.component = component
    return WebClient.builder().filter(filter).build()
  }

  @Override
  void check() {
    assert filter.count == 1
    assert filter.collected == "$component:"
  }
}
