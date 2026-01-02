package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.common.StrictForm;
import akka.http.scaladsl.model.HttpEntity;
import akka.http.scaladsl.unmarshalling.Unmarshaller;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

/**
 * @see akka.http.scaladsl.common.StrictForm$#unmarshaller(Unmarshaller, Unmarshaller)
 */
public class StrictFormCompanionInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.common.StrictForm$";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("unmarshaller"))
            .and(returns(named("akka.http.scaladsl.unmarshalling.Unmarshaller")))
            .and(takesArguments(2))
            .and(takesArgument(0, named("akka.http.scaladsl.unmarshalling.Unmarshaller")))
            .and(takesArgument(1, named("akka.http.scaladsl.unmarshalling.Unmarshaller"))),
        StrictFormCompanionInstrumentation.class.getName() + "$UnmarshallerAdvice");
  }

  static class UnmarshallerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) Unmarshaller<HttpEntity, StrictForm> ret) {
      ret = UnmarshallerHelpers.transformStrictFormUnmarshaller(ret);
    }
  }
}
