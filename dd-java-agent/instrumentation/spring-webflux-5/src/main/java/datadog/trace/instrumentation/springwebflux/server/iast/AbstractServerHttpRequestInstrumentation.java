package datadog.trace.instrumentation.springwebflux.server.iast;

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

@AutoService(Instrumenter.class)
public class AbstractServerHttpRequestInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public AbstractServerHttpRequestInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.http.server.reactive.AbstractServerHttpRequest";
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
