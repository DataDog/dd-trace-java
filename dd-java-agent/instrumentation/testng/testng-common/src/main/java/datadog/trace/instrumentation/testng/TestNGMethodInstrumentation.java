package datadog.trace.instrumentation.testng;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import net.bytebuddy.asm.Advice;
import org.testng.ITestNGMethod;
import org.testng.internal.annotations.IAnnotationFinder;

@AutoService(InstrumenterModule.class)
public class TestNGMethodInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public TestNGMethodInstrumentation() {
    super("testng", "test-retry");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isCiVisibilityExecutionPoliciesEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.testng.internal.TestNGMethod";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ConfigureRetryAdvice");
  }

  public static class ConfigureRetryAdvice {
    @Advice.OnMethodExit
    public static void configure(
        @Advice.This ITestNGMethod method,
        @Advice.FieldValue("m_annotationFinder") IAnnotationFinder annotationFinder) {
      TestNGUtils.configureRetryAnalyzer(method, annotationFinder);
    }
  }
}
