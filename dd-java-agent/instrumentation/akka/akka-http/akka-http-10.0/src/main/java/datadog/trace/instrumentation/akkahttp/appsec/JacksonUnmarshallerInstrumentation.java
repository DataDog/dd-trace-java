package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.javadsl.unmarshalling.Unmarshaller;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public class JacksonUnmarshallerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "akka.http.javadsl.marshallers.jackson.Jackson";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(returns(named("akka.http.javadsl.unmarshalling.Unmarshaller")))
            .and(named("byteStringUnmarshaller").or(named("unmarshaller")))
            .and(takesArguments(2))
            .and(takesArgument(0, named("com.fasterxml.jackson.databind.ObjectMapper")))
            .and(takesArgument(1, Class.class)),
        JacksonUnmarshallerInstrumentation.class.getName() + "$UnmarshallerAdvice");
  }

  static class UnmarshallerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) Unmarshaller ret) {
      ret = UnmarshallerHelpers.transformJacksonUnmarshaller(ret);
    }
  }
}
