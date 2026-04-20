package test;

import datadog.trace.agent.test.base.HttpServerTest;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@Filter(Filter.MATCH_ALL_PATTERN)
public class TestFilter implements HttpServerFilter {
  @Override
  public Publisher<MutableHttpResponse<?>> doFilter(
      HttpRequest<?> request, ServerFilterChain chain) {

    return Flux.from(chain.proceed(request))
        .doOnNext(
            res ->
                res.getHeaders()
                    .add(
                        HttpServerTest.getIG_RESPONSE_HEADER(),
                        HttpServerTest.getIG_RESPONSE_HEADER_VALUE()));
  }
}
