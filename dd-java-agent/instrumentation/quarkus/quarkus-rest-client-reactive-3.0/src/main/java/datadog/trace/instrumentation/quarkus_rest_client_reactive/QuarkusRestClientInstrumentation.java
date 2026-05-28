package datadog.trace.instrumentation.quarkus_rest_client_reactive;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

@AutoService(InstrumenterModule.class)
public final class QuarkusRestClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public QuarkusRestClientInstrumentation() {
    super("quarkus-rest-client-reactive", "quarkus-rest-client", "microprofile-rest-client");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.eclipse.microprofile.rest.client.RestClientBuilder";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".QuarkusRestClientDecorator",
      packageName + ".QuarkusRestClientTracingFilter",
      packageName + ".InjectAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("build")).and(takesArgument(0, Class.class)),
        QuarkusRestClientInstrumentation.class.getName() + "$RestClientBuilderAdvice");
  }

  public static class RestClientBuilderAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void registerFilter(@Advice.This final RestClientBuilder builder) {
      builder.register(QuarkusRestClientTracingFilter.class);
    }
  }
}
