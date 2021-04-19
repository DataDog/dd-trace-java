package datadog.trace.instrumentation.datanucleus;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.datanucleus.DatanucleusDecorator.DATANUCLEUS_QUERY_DELETE;
import static datadog.trace.instrumentation.datanucleus.DatanucleusDecorator.DATANUCLEUS_QUERY_EXECUTE;
import static datadog.trace.instrumentation.datanucleus.DatanucleusDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.datanucleus.api.jdo.JDOQuery;
import org.datanucleus.store.query.Query;

@AutoService(Instrumenter.class)
public class JDOQueryInstrumentation extends Instrumenter.Tracing {

  public JDOQueryInstrumentation() {
    super("datanucleus");
  }

  private final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassNamed("org.datanucleus.api.jdo.JDOQuery");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.datanucleus.api.jdo.JDOQuery");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatanucleusDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    // All of these methods delegate to *Internal() but have parameter checking and exceptions
    // beforehand.  Instrumenting all ensures we trace those exceptions.  Still instrumenting
    // *Internal() to futureproof the instrumentation
    return Collections.singletonMap(
        isMethod()
            .and(
                namedOneOf(
                    "execute",
                    "executeInternal",
                    "executeList",
                    "executeResultList",
                    "executeResultUnique",
                    "executeUnique",
                    "executeWithArray",
                    "executeWithMap",
                    "deletePersistentAll",
                    "deletePersistentInternal")),
        JDOQueryInstrumentation.class.getName() + "$QueryAdvice");
  }

  public static class QueryAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope startExecute(@Advice.Origin("#m") final String methodName) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(JDOQuery.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span =
          methodName.startsWith("execute")
              ? startSpan(DATANUCLEUS_QUERY_EXECUTE)
              : startSpan(DATANUCLEUS_QUERY_DELETE);

      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endExecute(
        @Advice.Enter final AgentScope scope,
        @Advice.FieldValue("query") Query internalQuery,
        @Advice.Thrown final Throwable throwable) {

      if (scope == null) {
        return;
      }

      CallDepthThreadLocalMap.reset(JDOQuery.class);

      AgentSpan span = scope.span();

      // candidateClass is set internally and is not always in sync with candidateClassName
      String candidateClassName =
          internalQuery.getCandidateClass() != null
              ? internalQuery.getCandidateClass().getName()
              : internalQuery.getCandidateClassName();

      DECORATE.setResourceFromIdOrClass(span, null, candidateClassName);
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);

      span.finish();
      scope.close();
    }
  }
}
