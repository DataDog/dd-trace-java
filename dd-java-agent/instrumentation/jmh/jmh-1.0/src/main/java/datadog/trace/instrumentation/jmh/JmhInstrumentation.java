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
    // Instrument BaseRunner (where the 'out' field is declared) so that the final-field write
    // in RunnerConstructorAdvice is legal: JDK 17+ rejects writing a final field declared in a
    // superclass from advice injected into the subclass (Runner).
    return "org.openjdk.jmh.runner.BaseRunner";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JmhUtils",
      packageName + ".DDOutputFormat",
      packageName + ".DatadogJmhReporter",
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
      out = new DDOutputFormat(out);
    }
  }
}
