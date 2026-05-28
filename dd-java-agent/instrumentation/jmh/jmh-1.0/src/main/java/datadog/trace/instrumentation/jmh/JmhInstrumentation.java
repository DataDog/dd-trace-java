package datadog.trace.instrumentation.jmh;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.openjdk.jmh.runner.format.OutputFormat;

@AutoService(InstrumenterModule.class)
public class JmhInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JmhInstrumentation() {
    super("ci-visibility", "jmh");
  }

  @Override
  public String instrumentedType() {
    return "org.openjdk.jmh.runner.Runner";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JmhUtils", packageName + ".DDOutputFormat",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("org.openjdk.jmh.runner.options.Options")))
            .and(takesArgument(1, named("org.openjdk.jmh.runner.format.OutputFormat"))),
        JmhInstrumentation.class.getName() + "$RunnerConstructorAdvice");
  }

  public static class RunnerConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.FieldValue(value = "out", readOnly = false) OutputFormat out) {
      if (out instanceof DDOutputFormat) {
        return;
      }
      String version;
      try {
        version = org.openjdk.jmh.Main.class.getPackage().getImplementationVersion();
      } catch (Throwable t) {
        version = null;
      }
      out = new DDOutputFormat(out, version != null ? version : "unknown");
    }
  }
}
