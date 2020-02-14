package datadog.trace.instrumentation.springwebflux.server;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.web.server.WebFilter;

public class FilterCustomizer implements Consumer<List<WebFilter>> {
  public static Consumer<List<WebFilter>> INSTANCE = new FilterCustomizer();

  @Override
  public void accept(final List<WebFilter> filters) {
    filters.add(0, new TracingWebFilter());
  }
}
