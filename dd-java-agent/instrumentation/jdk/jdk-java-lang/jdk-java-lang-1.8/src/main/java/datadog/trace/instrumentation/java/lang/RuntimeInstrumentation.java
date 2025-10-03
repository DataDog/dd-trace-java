package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import java.io.File;

@AutoService(InstrumenterModule.class)
public class RuntimeInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, Instrumenter.ForBootstrap {

  public RuntimeInstrumentation() {
    super("java-lang-appsec");
  }

  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled()
        && !Platform.isNativeImageBuilder(); // not applicable in native-image
  }

  @Override
  public String instrumentedType() {
    return "java.lang.Runtime";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("exec").and(takesArguments(String.class, String[].class, File.class)),
        packageName + ".RuntimeExecStringAdvice");
  }
}
