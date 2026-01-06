package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import play.libs.F;
import play.libs.Files;
import play.mvc.Http;
import play.mvc.Result;

public class JavaMultipartFormDataRegisterExcF
    implements Function<Throwable, F.Either<Result, Http.MultipartFormData<Files.TemporaryFile>>> {
  public static Function<Throwable, F.Either<Result, Http.MultipartFormData<Files.TemporaryFile>>>
      INSTANCE = new JavaMultipartFormDataRegisterExcF();

  private JavaMultipartFormDataRegisterExcF() {}

  @Override
  public F.Either<Result, Http.MultipartFormData<Files.TemporaryFile>> apply(Throwable exc) {
    if (exc instanceof CompletionException) {
      exc = exc.getCause();
    }
    if (exc instanceof BlockingException) {
      AgentSpan agentSpan = activeSpan();
      if (agentSpan != null) {
        agentSpan.addThrowable(exc);
      }
    }
    if (exc instanceof RuntimeException) {
      throw (RuntimeException) exc;
    } else {
      throw new UndeclaredThrowableException(exc);
    }
  }
}
