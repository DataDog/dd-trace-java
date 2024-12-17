package datadog.trace.instrumentation.weaver;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import weaver.framework.SuiteEvent;

@AutoService(InstrumenterModule.class)
public class WeaverInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType {
  // implements Instrumenter.ForTypeHierarchy {

  public WeaverInstrumentation() {
    super("ci-visibility", "weaver");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems);
  }

  // @Override
  // public String hierarchyMarkerType() {
  //  return null;
  // }

  // @Override
  // public ElementMatcher<TypeDescription> hierarchyMatcher() {
  //  return implementsInterface(named("weaver.framework.RunnerCompat.SuiteEventBroker"));
  // }

  @Override
  public String instrumentedType() {
    return "weaver.framework.RunnerCompat.ConcurrentQueueEventBroker";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogWeaverReporter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("send"), WeaverInstrumentation.class.getName() + "$SendEventAdvice");
  }

  public static class SendEventAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onSendEvent(@Advice.Argument(value = 0) SuiteEvent event) {
      DatadogWeaverReporter.handle(event);
    }
  }
}
