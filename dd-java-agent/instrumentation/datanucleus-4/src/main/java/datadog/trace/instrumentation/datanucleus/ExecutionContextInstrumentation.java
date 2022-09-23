package datadog.trace.instrumentation.datanucleus;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.datanucleus.DatanucleusDecorator.DATANUCLEUS_FIND_OBJECT;
import static datadog.trace.instrumentation.datanucleus.DatanucleusDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.datanucleus.ExecutionContext;

@AutoService(Instrumenter.class)
public class ExecutionContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {
  public ExecutionContextInstrumentation() {
    super("datanucleus");
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.datanucleus.ExecutionContextImpl", "org.datanucleus.ExecutionContextThreadedImpl"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.datanucleus.ExecutionContext";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatanucleusDecorator",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(namedOneOf("persistObject", "deleteObject", "refreshObject")),
        ExecutionContextInstrumentation.class.getName() + "$SingleObjectActionAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(namedOneOf("refreshAllObjects", "persistObjects", "deleteObjects", "findObjects")),
        ExecutionContextInstrumentation.class.getName() + "$MultiObjectActionAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("findObject"))
            .and(takesArguments(4))
            .and(takesArgument(3, String.class)),
        ExecutionContextInstrumentation.class.getName() + "$FindWithStringClassnameAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("findObject"))
            .and(takesArguments(4))
            .and(takesArgument(2, Class.class)),
        ExecutionContextInstrumentation.class.getName() + "$FindWithClassAdvice");
  }

  public static class MultiObjectActionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope startMethod(
        @Advice.This final ExecutionContext executionContext,
        @Advice.Origin("datanucleus.#m") final String operationName) {

      final AgentSpan span = startSpan(operationName);
      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
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

  public static class SingleObjectActionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope startMethod(
        @Advice.Origin("datanucleus.#m") final String operationName,
        @Advice.Argument(0) Object entity) {

      if (entity == null) {
        return null;
      }

      final AgentSpan span = startSpan(operationName);
      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Argument(0) Object entity) {

      if (scope == null) {
        return;
      }

      AgentSpan span = scope.span();
      DECORATE.onError(span, throwable);
      DECORATE.onOperation(span, entity);
      DECORATE.beforeFinish(span);

      span.finish();
      scope.close();
    }
  }

  public static class FindWithStringClassnameAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope startMethod() {

      final AgentSpan span = startSpan(DATANUCLEUS_FIND_OBJECT);
      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Argument(0) Object id,
        @Advice.Argument(3) String objectClassName) {

      if (scope == null) {
        return;
      }

      AgentSpan span = scope.span();
      DECORATE.setResourceFromIdOrClass(span, id, objectClassName);
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);

      span.finish();
      scope.close();
    }
  }

  public static class FindWithClassAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope startMethod() {

      final AgentSpan span = startSpan(DATANUCLEUS_FIND_OBJECT);
      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Argument(0) Object id,
        @Advice.Argument(2) Class cls) {

      if (scope == null) {
        return;
      }

      AgentSpan span = scope.span();
      DECORATE.setResourceFromIdOrClass(span, id, cls == null ? null : cls.getName());
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);

      span.finish();
      scope.close();
    }
  }
}
