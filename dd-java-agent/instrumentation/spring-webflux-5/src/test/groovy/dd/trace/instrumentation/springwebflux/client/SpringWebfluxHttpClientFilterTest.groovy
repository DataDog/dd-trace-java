package dd.trace.instrumentation.springwebflux.client

import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
class SpringWebfluxHttpClientFilterTest extends SpringWebfluxHttpClientBase {

  CollectingFilter filter = null
  String component = ""

  @Override
  WebClient createClient(CharSequence component, InetSocketAddress proxy) {
    filter = new CollectingFilter()
    this.component = component
    return WebClient.builder().clientConnector(new AnyCertConnector(proxy)).filter(filter).build()
  }

  @Override
  void check() {
    assert filter.count == 1
    assert filter.collected == "$component:"
  }
}
