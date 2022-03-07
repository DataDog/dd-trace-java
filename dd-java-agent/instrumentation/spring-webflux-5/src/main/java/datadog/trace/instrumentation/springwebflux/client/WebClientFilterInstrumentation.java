package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class WebClientFilterInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public WebClientFilterInstrumentation() {
    super("spring-webflux", "spring-webflux-client");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebfluxHttpClientDecorator",
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
  public void adviceTransformations(AdviceTransformation transformation) {
    // This one can't possibly happen on multiple threads so makes sure we are always added to the
    // list initially
    transformation.applyAdvice(
        isConstructor(), packageName + ".WebClientFilterAdvices$AfterConstructorAdvice");
    // These methods are not thread safe already so doing our work here shouldn't change the
    // likelihood of ConcurrentModificationException happening
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("filter").or(named("filters"))),
        packageName + ".WebClientFilterAdvices$AfterFilterListModificationAdvice");
  }
}
