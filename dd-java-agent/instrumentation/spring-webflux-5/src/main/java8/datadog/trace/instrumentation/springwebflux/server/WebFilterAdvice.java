package datadog.trace.instrumentation.springwebflux.server;

import net.bytebuddy.asm.Advice;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

public class WebFilterAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void methodEnter(@Advice.This final WebHttpHandlerBuilder thiz) {
    thiz.filters(FilterCustomizer.INSTANCE);
  }
}
