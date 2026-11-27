package datadog.trace.instrumentation.logback;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;

@AutoService(InstrumenterModule.class)
public class PatternLayoutInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.ForBootstrap, Instrumenter.HasMethodAdvice {
  public PatternLayoutInstrumentation() {
    super("logback");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogPatternInject();
  }

  @Override
  public String instrumentedType() {
    // start() is inherited by both PatternLayout and its application subclasses.
    return "ch.qos.logback.core.pattern.PatternLayoutBase";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("start")).and(takesArguments(0)),
        packageName + ".PatternLayoutStartAdvice");
  }
}
