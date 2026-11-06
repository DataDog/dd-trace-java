package datadog.trace.instrumentation.log4j2;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;

@AutoService(InstrumenterModule.class)
public class PatternLayoutInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public PatternLayoutInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogPatternInject();
  }

  @Override
  public String instrumentedType() {
    return "org.apache.logging.log4j.core.layout.PatternLayout";
  }

  @Override
  public String muzzleDirective() {
    return "pattern-layout";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(2, String.class)),
        packageName + ".PatternLayoutConstructorAdvice");
  }
}
