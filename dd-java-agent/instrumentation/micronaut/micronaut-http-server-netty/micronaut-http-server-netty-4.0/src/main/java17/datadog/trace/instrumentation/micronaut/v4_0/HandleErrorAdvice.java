package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.DECORATE;

import io.micronaut.http.HttpRequest;
import net.bytebuddy.asm.Advice;

/** Micronaut 4.3+: {@code RequestLifecycle.onErrorNoFilter(HttpRequest, Throwable, ...)}. */
public class HandleErrorAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void captureError(
      @Advice.Argument(0) final HttpRequest<?> request, @Advice.Argument(1) final Throwable cause) {
    DECORATE.onRoutedError(request, cause);
  }
}
