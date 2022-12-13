package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;

@AutoService(Instrumenter.class)
public class ProcessImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.ForBootstrap {

  public ProcessImplInstrumentation() {
    super("java-lang-appsec");
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
