package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class KotlinCoroutinesModule extends InstrumenterModule.ContextTracking {
  public KotlinCoroutinesModule() {
    super("kotlin_coroutine");
  }

  /** Includes the synthetic flow collection boundary used by older coroutine releases. */
  @Override
  public ElementMatcher<? super MethodDescription> methodIgnoreMatcher() {
    return isSynthetic().and(not(named("collectWithContextUndispatched")));
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new CoroutineContextInstrumentation(),
        new CoroutineInstrumentation(),
        new LazyCoroutineInstrumentation(),
        new CoroutineFlowInstrumentation(),
        new CoroutineFlowContextCheckInstrumentation());
  }
}
