package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator.DECORATE;

import io.micronaut.http.HttpRequest;
import net.bytebuddy.asm.Advice;

/** Micronaut 4.0-4.2: {@code RequestLifecycle.onErrorNoFilter(Throwable, PropagatedContext)}. */
public class HandleErrorAdvice2 {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void captureError(
      @Advice.FieldValue("request") final HttpRequest<?> request,
      @Advice.Argument(0) final Throwable cause) {
    DECORATE.onRoutedError(request, cause);
  }
}
