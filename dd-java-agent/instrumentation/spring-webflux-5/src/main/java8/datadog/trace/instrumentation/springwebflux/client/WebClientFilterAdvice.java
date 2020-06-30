package datadog.trace.instrumentation.springwebflux.client;

import net.bytebuddy.asm.Advice;
import org.springframework.web.reactive.function.client.WebClient;

public class WebClientFilterAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onBuild(@Advice.This final WebClient.Builder thiz) {
    thiz.filters(WebClientTracingFilter::addFilter);
  }
}
