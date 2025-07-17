package datadog.trace.instrumentation.akkahttp106;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public final class AkkaHttpSingleRequestInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public AkkaHttpSingleRequestInstrumentation() {
    super("akka-http", "akka-http-client");
  }

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.HttpExt";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AkkaHttpClientHelpers",
      packageName + ".AkkaHttpClientHelpers$OnCompleteHandler",
      packageName + ".AkkaHttpClientHelpers$AkkaHttpHeaders",
      packageName + ".AkkaHttpClientHelpers$HasSpanHeader",
      packageName + ".AkkaHttpClientDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("singleRequest").and(takesArgument(0, named("akka.http.scaladsl.model.HttpRequest"))),
        packageName + ".SingleRequestAdvice");
  }
}
