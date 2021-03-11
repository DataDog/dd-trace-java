package dd.trace.instrumentation.springwebflux.client

import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
class SpringWebfluxHttpClientBuilderReuseTest extends SpringWebfluxHttpClientBase {

  @Override
  WebClient createClient(CharSequence component, InetSocketAddress proxy) {
    def builder = WebClient.builder().clientConnector(new AnyCertConnector(proxy))
    builder.build()
    return builder.build()
  }

  @Override
  void check() {
  }
}
