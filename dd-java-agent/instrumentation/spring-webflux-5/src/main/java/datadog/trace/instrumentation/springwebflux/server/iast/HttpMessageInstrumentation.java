package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpMessageInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  public HttpMessageInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.http.HttpMessage";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("getHeaders")).and(takesArguments(0)),
        getClass().getName() + "$TaintHeadersAdvice");
  }

  public static class TaintHeadersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.Return Object object) {
      PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null) {
        return;
      }
      propagation.taint(object, SourceTypes.REQUEST_HEADER_VALUE);
    }
  }
}
