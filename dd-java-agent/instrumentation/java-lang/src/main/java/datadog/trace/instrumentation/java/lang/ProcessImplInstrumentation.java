package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import java.util.Map;

@AutoService(Instrumenter.class)
public class ProcessImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.ForBootstrap {

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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("start")
            .and(
                takesArguments(
                    String[].class,
                    Map.class,
                    String.class,
                    ProcessBuilder.Redirect[].class,
                    boolean.class)),
        packageName + ".ProcessImplStartAdvice");
  }
}
