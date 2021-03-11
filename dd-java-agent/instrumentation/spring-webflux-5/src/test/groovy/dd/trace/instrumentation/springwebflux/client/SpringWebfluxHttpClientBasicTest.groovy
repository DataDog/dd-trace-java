package dd.trace.instrumentation.springwebflux.client


import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
class SpringWebfluxHttpClientBasicTest extends SpringWebfluxHttpClientBase {

  @Override
  WebClient createClient(CharSequence component, InetSocketAddress proxy) {
    return WebClient.builder().clientConnector(new AnyCertConnector(proxy)).build()
  }

  @Override
  void check() {
  }
}
