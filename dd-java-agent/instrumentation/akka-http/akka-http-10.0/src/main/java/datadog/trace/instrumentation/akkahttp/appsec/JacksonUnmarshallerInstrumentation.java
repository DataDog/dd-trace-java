package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.javadsl.unmarshalling.Unmarshaller;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JacksonUnmarshallerInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JacksonUnmarshallerInstrumentation() {
    super("akka-http");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".UnmarshallerHelpers",
      packageName + ".UnmarshallerHelpers$UnmarkStrictFormOngoingOnUnsupportedException",
      packageName + ".AkkaBlockResponseFunction",
      packageName + ".BlockingResponseHelper",
      packageName + ".ScalaListCollector",
      "datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator",
      "datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders",
      "datadog.trace.instrumentation.akkahttp.UriAdapter",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return ScalaListCollectorMuzzleReferences.additionalMuzzleReferences();
  }

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
