package datadog.trace.instrumentation.mule4;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;

@AutoService(InstrumenterModule.class)
public class JpmsMuleInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.HasMethodAdvice, Instrumenter.ForKnownTypes {
  public JpmsMuleInstrumentation() {
    super("mule", "mule-jpms");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Platform.isJavaVersionAtLeast(9);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      // same module but they can be initialized in any order
      "org.mule.runtime.tracer.customization.impl.info.ExecutionInitialSpanInfo",
      "org.mule.runtime.tracer.customization.impl.provider.LazyInitialSpanInfo",
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JpmsAdvisingHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // it does not work with typeInitializer()
    transformer.applyAdvice(isConstructor(), packageName + ".JpmsClearanceAdvice");
  }
}
