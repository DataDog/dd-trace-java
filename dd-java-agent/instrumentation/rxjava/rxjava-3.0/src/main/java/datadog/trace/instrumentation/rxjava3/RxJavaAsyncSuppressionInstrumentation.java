package datadog.trace.instrumentation.rxjava3;

import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.async.AsyncPropagationSuppressingInstrumentation;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Prevents FINISHED/DISPOSED sentinel FutureTasks from capturing the active request context. */
@AutoService(InstrumenterModule.class)
public final class RxJavaAsyncSuppressionInstrumentation
    extends AsyncPropagationSuppressingInstrumentation implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "io.reactivex.rxjava3.internal.schedulers.AbstractDirectTask";
  }

  @Override
  protected ElementMatcher<? super MethodDescription> suppressedMethods() {
    return isTypeInitializer();
  }
}
