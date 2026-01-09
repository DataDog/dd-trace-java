package datadog.trace.instrumentation.junit5.execution;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.util.Strings;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

@AutoService(InstrumenterModule.class)
public class JUnit5NodeTestTaskContextInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String parentPackageName =
      Strings.getPackageName(JUnitPlatformUtils.class.getName());

  public JUnit5NodeTestTaskContextInstrumentation() {
    super("ci-visibility", "junit-5", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityExecutionPoliciesEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.junit.platform.engine.support.hierarchical.NodeTestTaskContext";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ThrowableCollectorFactoryWrapper", parentPackageName + ".JUnitPlatformUtils"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        JUnit5NodeTestTaskContextInstrumentation.class.getName() + "$BeforeConstructor");
  }

  public static class BeforeConstructor {
    @Advice.OnMethodEnter
    public static void replaceThrowableCollectorFactory(
        @Advice.Argument(value = 2, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            ThrowableCollector.Factory throwableCollectorFactory) {
      throwableCollectorFactory = new ThrowableCollectorFactoryWrapper(throwableCollectorFactory);
    }
  }
}
