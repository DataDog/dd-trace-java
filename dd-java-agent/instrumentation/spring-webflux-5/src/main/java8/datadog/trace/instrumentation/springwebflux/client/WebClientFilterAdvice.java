package datadog.trace.instrumentation.springwebflux.client;

import net.bytebuddy.asm.Advice;
import org.springframework.web.reactive.function.client.WebClient;

public class WebClientFilterAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void methodEnter(@Advice.This final WebClient.Builder thiz) {
    thiz.filters(filters -> filters.add(0, new WebClientTracingFilter()));
  }
}
