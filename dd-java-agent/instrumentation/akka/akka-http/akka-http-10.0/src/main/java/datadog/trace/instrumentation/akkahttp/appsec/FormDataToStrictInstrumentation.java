package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.stream.Materializer;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import scala.concurrent.duration.FiniteDuration;

/**
 * @see akka.http.scaladsl.model.Multipart.FormData#toStrict(FiniteDuration, Materializer)
 */
public class FormDataToStrictInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.model.Multipart$FormData";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("toStrict"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("scala.concurrent.duration.FiniteDuration")))
            .and(takesArgument(1, named("akka.stream.Materializer")))
            .and(returns(named("scala.concurrent.Future"))),
        FormDataToStrictInstrumentation.class.getName() + "$ToStrictAdvice");
  }

  static class ToStrictAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void before(
        @Advice.Return(readOnly = false)
            scala.concurrent.Future<akka.http.scaladsl.model.Multipart$FormData$Strict> fut,
        @Advice.Argument(1) Materializer mat) {
      fut = UnmarshallerHelpers.transformMultiPartFormDataToStrictFuture(fut, mat);
    }
  }
}
