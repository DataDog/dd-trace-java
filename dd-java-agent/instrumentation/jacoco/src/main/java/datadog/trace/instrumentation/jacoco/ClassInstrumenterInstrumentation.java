package datadog.trace.instrumentation.jacoco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ClassInstrumenterInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {
  public ClassInstrumenterInstrumentation() {
    super("jacoco");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityCodeCoverageEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.jacoco.agent.rt.IAgent";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // The jacoco javaagent jar that is published relocates internal classes to an "obfuscated"
    // package name ex. org.jacoco.agent.rt.internal_72ddf3b.core.internal.instr.ClassInstrumenter
    return nameStartsWith("org.jacoco.agent.rt.internal")
        .and(nameEndsWith(".core.internal.instr.ClassInstrumenter"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("visitTotalProbeCount")),
        getClass().getName() + "$VisitTotalProbeCountAdvice");
  }

  public static class VisitTotalProbeCountAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(
        @Advice.FieldValue(value = "className") final String className,
        @Advice.Argument(0) int count) {
      InstrumentationBridge.getCoverageProbeStoreRegistry().setTotalProbeCount(className, count);
    }
  }
}
