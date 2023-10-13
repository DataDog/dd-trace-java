package datadog.trace.instrumentation.springwebflux.client;

import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

public class WebClientFilterAdvices {
  public static class AfterConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.FieldValue(value = "filters", readOnly = false)
            List<ExchangeFilterFunction> filters) {
      if (filters == null) {
        filters = new ArrayList<>();
      }
      WebClientTracingFilter.addFilter(filters);
    }
  }

  public static class AfterFilterListModificationAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.FieldValue(value = "filters") final List<ExchangeFilterFunction> filters) {
      WebClientTracingFilter.addFilter(filters);
    }
  }
}
