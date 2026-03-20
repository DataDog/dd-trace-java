package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;

@AutoService(InstrumenterModule.class)
public class ProcessBuilderSessionIdInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ProcessBuilderSessionIdInstrumentation() {
    super("process-session-id");
  }

  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled() && !Platform.isNativeImageBuilder();
  }

  @Override
  public String instrumentedType() {
    return "java.lang.ProcessBuilder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("start").and(takesNoArguments()),
        packageName + ".ProcessBuilderSessionIdAdvice");
  }
}
