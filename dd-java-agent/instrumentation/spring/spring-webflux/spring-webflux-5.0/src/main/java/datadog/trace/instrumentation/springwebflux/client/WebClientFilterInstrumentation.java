package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class WebClientFilterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public WebClientFilterInstrumentation() {
    super("spring-webflux", "spring-webflux-client");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebfluxHttpClientDecorator",
      packageName + ".StatusCodes",
      packageName + ".TraceWebClientSubscriber",
      packageName + ".WebClientTracingFilter",
      packageName + ".WebClientTracingFilter$MonoWebClientTrace",
    };
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.reactive.function.client.DefaultWebClientBuilder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // This one can't possibly happen on multiple threads so makes sure we are always added to the
    // list initially
    transformer.applyAdvice(
        isConstructor(), packageName + ".WebClientFilterAdvices$AfterConstructorAdvice");
    // These methods are not thread safe already so doing our work here shouldn't change the
    // likelihood of ConcurrentModificationException happening
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("filter").or(named("filters"))),
        packageName + ".WebClientFilterAdvices$AfterFilterListModificationAdvice");
  }
}
