package datadog.trace.instrumentation.java.completablefuture;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.java.completablefuture.CompletableFutureUniCompletionInstrumentation.*;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Described in {@link CompletableFutureUniCompletionInstrumentation} */
@AutoService(InstrumenterModule.class)
public class CompletableFutureUniCompletionSubclassInstrumentation
    extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {

  public CompletableFutureUniCompletionSubclassInstrumentation() {
    super("java_completablefuture", "java_concurrent");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith(COMPLETABLE_FUTURE).and(extendsClass(named(UNI_COMPLETION)));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(UNI_COMPLETION, ConcurrentState.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("tryFire").and(takesArguments(int.class)), ADVICE_BASE + "UniSubTryFire");
  }
}
