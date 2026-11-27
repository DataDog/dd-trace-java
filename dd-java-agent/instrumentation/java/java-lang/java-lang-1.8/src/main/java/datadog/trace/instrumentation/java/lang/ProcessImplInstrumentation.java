package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;

@AutoService(InstrumenterModule.class)
public class ProcessImplInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, Instrumenter.ForBootstrap {

  public ProcessImplInstrumentation() {
    super("java-lang-appsec");
  }

  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled()
        && !Platform.isNativeImageBuilder(); // not applicable in native-image
  }

  @Override
  public String instrumentedType() {
    return "java.lang.ProcessImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("start")
            .and(
                hasDescriptor(
                    "([Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;[Ljava/lang/ProcessBuilder$Redirect;Z)Ljava/lang/Process;")),
        packageName + ".ProcessImplStartAdvice");
  }
}
