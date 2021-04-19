package datadog.trace.instrumentation.datanucleus;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.datanucleus.DatanucleusDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class JDOTransactionInstrumentation extends Instrumenter.Tracing {

  public JDOTransactionInstrumentation() {
    super("datanucleus");
  }

  private final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassNamed("org.datanucleus.api.jdo.JDOTransaction");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.datanucleus.api.jdo.JDOTransaction");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatanucleusDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {

    return Collections.singletonMap(
        isMethod().and(namedOneOf("commit", "rollback")),
        JDOTransactionInstrumentation.class.getName() + "$TransactionAdvice");
  }

  public static class TransactionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope start(
        @Advice.Origin("datanucleus.transaction.#m") final String operationName) {
      final AgentSpan span = startSpan(operationName);

      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void end(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {

      if (scope == null) {
        return;
      }

      AgentSpan span = scope.span();

      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);

      span.finish();
      scope.close();
    }
  }
}
