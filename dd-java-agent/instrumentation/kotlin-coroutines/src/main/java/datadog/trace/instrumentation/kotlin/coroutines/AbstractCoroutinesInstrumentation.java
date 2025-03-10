package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextHelper.closeDatadogContext;
import static datadog.trace.instrumentation.kotlin.coroutines.CoroutineContextHelper.initializeDatadogContext;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isOverriddenFrom;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.Map;
import kotlinx.coroutines.AbstractCoroutine;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class AbstractCoroutinesInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {

  protected static final String ABSTRACT_COROUTINE_CLASS_NAME =
      "kotlinx.coroutines.AbstractCoroutine";

  protected static final String JOB_SUPPORT_CLASS_NAME = "kotlinx.coroutines.JobSupport";
  protected static final String COROUTINE_CONTEXT_CLASS_NAME = "kotlin.coroutines.CoroutineContext";

  public AbstractCoroutinesInstrumentation() {
    super("kotlin_coroutine.experimental");
  }

  @Override
  protected final boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogCoroutineContext",
      packageName + ".DatadogCoroutineContext$ContextElementKey",
      packageName + ".DatadogCoroutineContext$DatadogCoroutineContextItem",
      packageName + ".CoroutineContextHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("onStart")).and(takesNoArguments()).and(returns(void.class)),
        AbstractCoroutinesInstrumentation.class.getName() + "$AbstractCoroutineOnStartAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isOverriddenFrom(named(JOB_SUPPORT_CLASS_NAME)))
            .and(named("onCompletionInternal"))
            .and(takesArguments(1))
            .and(returns(void.class)),
        AbstractCoroutinesInstrumentation.class.getName()
            + "$JobSupportAfterCompletionInternalAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return JOB_SUPPORT_CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return extendsClass(named(ABSTRACT_COROUTINE_CLASS_NAME));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "kotlinx.coroutines.Job",
        packageName + ".DatadogCoroutineContext$DatadogCoroutineContextItem");
  }

  /**
   * If/when coroutine is started lazily, initializes DatadogCoroutineContext element on coroutine
   * start
   *
   * @see DatadogCoroutineContext
   * @see AbstractCoroutine#onStart()
   */
  public static class AbstractCoroutineOnStartAdvice {
    @Advice.OnMethodEnter
    public static void onStartInvocation(@Advice.This final AbstractCoroutine<?> coroutine) {
      // try to inherit parent span from the coroutine start call site
      initializeDatadogContext(coroutine);
    }
  }

  /**
   * Guarantees a DatadogCoroutineContext element is always closed when coroutine transitions into a
   * terminal state.
   *
   * @see DatadogCoroutineContext
   * @see AbstractCoroutine#onCompletionInternal(Object)
   */
  public static class JobSupportAfterCompletionInternalAdvice {
    @Advice.OnMethodEnter
    public static void onCompletionInternal(@Advice.This final AbstractCoroutine<?> coroutine) {
      closeDatadogContext(coroutine);
    }
  }
}
