package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.AbstractCoroutine;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class KotlinCoroutines13Instrumentation extends AbstractCoroutinesInstrumentation {

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    super.adviceTransformations(transformation);
    transformation.applyAdvice(
        isConstructor()
            .and(isDeclaredBy(named(ABSTRACT_COROUTINE_CLASS_NAME)))
            .and(takesArguments(2))
            .and(takesArgument(0, named(COROUTINE_CONTEXT_CLASS_NAME)))
            .and(takesArgument(1, named("boolean"))),
        KotlinCoroutines13Instrumentation.class.getName() + "$AbstractCoroutineConstructorAdvice");
  }

  /**
   * Guarantees every coroutine created has a new instance of ScopeStateCoroutineContext, so that it
   * is never inherited from the parent context.
   *
   * @see ScopeStateCoroutineContext
   * @see AbstractCoroutine#AbstractCoroutine(CoroutineContext, boolean)
   */
  public static class AbstractCoroutineConstructorAdvice {
    @Advice.OnMethodEnter
    public static void constructorInvocation(
        @Advice.Argument(value = 0, readOnly = false) CoroutineContext parentContext,
        @Advice.Argument(value = 1) final boolean active) {
      final ScopeStateCoroutineContext scopeStackContext = new ScopeStateCoroutineContext();
      parentContext = parentContext.plus(scopeStackContext);
      if (active) {
        // if this is not a lazy coroutine, inherit parent span from the coroutine constructor call
        // site
        scopeStackContext.maybeInitialize();
      }
    }
  }
}
