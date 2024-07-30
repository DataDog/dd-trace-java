package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.kotlin.coroutines.DDContextElement.contextFromCoroutineContext;
import static java.util.Collections.emptyMap;
import static net.bytebuddy.asm.Advice.Argument;
import static net.bytebuddy.asm.Advice.OnMethodEnter;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter.ForSingleType;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.InstrumenterModule.Tracing;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.Map;
import kotlin.coroutines.CoroutineContext;

@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class CoroutineContextInstrumentation extends Tracing
    implements ForSingleType, ExcludeFilterProvider {
  static final String INSTRUMENTATION_NAME = "kotlin-coroutines.experimental";
  static final String LEGACY_INSTRUMENTATION_NAME = "kotlin_coroutine.experimental";

  public CoroutineContextInstrumentation() {
    super(INSTRUMENTATION_NAME, LEGACY_INSTRUMENTATION_NAME);
  }

  // TODO
  //  @Override
  //  protected boolean defaultEnabled() {
  //    return false;
  //  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DDContextElement", packageName + ".DDContextElement$1",
    };
  }

  @Override
  public String instrumentedType() {
    return "kotlinx.coroutines.CoroutineContextKt";
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return emptyMap();
    //    return singletonMap(RUNNABLE, singletonList("kotlinx.coroutines.DispatchedTask"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("newCoroutineContext")
            .and(takesArgument(0, named("kotlinx.coroutines.CoroutineScope")))
            .and(takesArgument(1, named("kotlin.coroutines.CoroutineContext"))),
        CoroutineContextInstrumentation.class.getName() + "$CoroutineContextAdvice");
  }

  @SuppressWarnings("unused")
  public static class CoroutineContextAdvice {

    @OnMethodEnter
    public static void enter(
        @Argument(value = 1, readOnly = false) CoroutineContext coroutineContext) {
      if (coroutineContext != null) {
        Context current = Context.current();
        Context inCoroutine = contextFromCoroutineContext(coroutineContext);
        if (current != inCoroutine && Context.empty().equals(inCoroutine)) {
          coroutineContext = coroutineContext.plus(new DDContextElement(current));
        }
      }
    }
  }
}
