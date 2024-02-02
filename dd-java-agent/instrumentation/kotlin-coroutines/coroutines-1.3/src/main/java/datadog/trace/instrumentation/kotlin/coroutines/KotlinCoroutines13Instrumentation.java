package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextHelper.getScopeStateContext;
import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextHelper.initializeScopeStateContextIfActive;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.kotlin.coroutines.ScopeStateCoroutineContext.ScopeStateCoroutineContextItem;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.Job;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class KotlinCoroutines13Instrumentation extends AbstractCoroutinesInstrumentation {

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    super.methodAdvice(transformer);
    transformer.applyAdvice(
        isConstructor()
            .and(isDeclaredBy(named(ABSTRACT_COROUTINE_CLASS_NAME)))
            .and(takesArguments(2))
            .and(takesArgument(0, named(COROUTINE_CONTEXT_CLASS_NAME)))
            .and(takesArgument(1, named("boolean"))),
        KotlinCoroutines13Instrumentation.class.getName() + "$AbstractCoroutineConstructorAdvice");
  }

  /**
   * Guarantees every coroutine created has an instance of ScopeStateCoroutineContext
   *
   * @see ScopeStateCoroutineContext
   * @see AbstractCoroutine#AbstractCoroutine(CoroutineContext, boolean)
   */
  public static class AbstractCoroutineConstructorAdvice {
    @Advice.OnMethodEnter
    public static void constructorInvocation(
        @Advice.Argument(value = 0, readOnly = false) CoroutineContext parentContext) {
      final ScopeStateCoroutineContext scopeStackContext = getScopeStateContext(parentContext);
      if (scopeStackContext == null) {
        parentContext =
            parentContext.plus(
                new ScopeStateCoroutineContext(
                    InstrumentationContext.get(Job.class, ScopeStateCoroutineContextItem.class)));
      }
    }

    @Advice.OnMethodExit
    public static void constructorInvocationOnMethodExit(
        @Advice.This final AbstractCoroutine<?> coroutine,
        @Advice.Argument(value = 1) final boolean active) {
      // if this is not a lazy coroutine, inherit parent span from
      // the coroutine constructor call site
      initializeScopeStateContextIfActive(coroutine, active);
    }
  }
}
