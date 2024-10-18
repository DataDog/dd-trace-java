package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextInstrumentation.INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextInstrumentation.LEGACY_INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter.ForTypeHierarchy;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.InstrumenterModule.Tracing;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class CoroutineDispatcherInstrumentation extends Tracing implements ForTypeHierarchy {
  public CoroutineDispatcherInstrumentation() {
    super(INSTRUMENTATION_NAME, LEGACY_INSTRUMENTATION_NAME);
  }

  // TODO
  //  @Override
  //  protected boolean defaultEnabled() {
  //    return false;
  //  }

  @Override
  public String hierarchyMarkerType() {
    return "kotlinx.coroutines.CoroutineDispatcher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("dispatch").and(takesArgument(1, Runnable.class)),
        CoroutineDispatcherInstrumentation.class.getName() + "$CoroutineDispatcherAdvice");
  }

  @SuppressWarnings("unused")
  public static class CoroutineDispatcherAdvice {
    @OnMethodEnter
    public static void enter(@Argument(value = 1, readOnly = false) Runnable runnable) {
      if (runnable != null) {
        final Runnable capturedRunnable = runnable;
        runnable =
            () -> {
              try (ContextScope ignored = Context.empty().makeCurrent()) {
                capturedRunnable.run();
              }
            };
      }
    }
  }
}
