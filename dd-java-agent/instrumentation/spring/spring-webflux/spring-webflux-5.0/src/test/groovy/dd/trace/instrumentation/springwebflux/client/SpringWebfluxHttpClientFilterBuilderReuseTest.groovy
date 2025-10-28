package dd.trace.instrumentation.springwebflux.client

import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

@Timeout(5)
class SpringWebfluxHttpClientFilterBuilderReuseTest extends SpringWebfluxHttpClientBase {

  CollectingFilter filter = null
  String component = ""

  @Override
  WebClient createClient(CharSequence component) {
    this.filter = new CollectingFilter()
    this.component = component
    def builder = WebClient.builder()
    // add filters before and after, and check that we move our filter first
    builder.filter(filter)
    builder.build()
    builder.filters({
      it.add(0, filter)
    })

    return builder.build()
  }

  @Override
  void check() {
    assert filter.count == 2
    assert filter.collected == "$component:$component:"
  }
}
