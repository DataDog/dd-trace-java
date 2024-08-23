package datadog.trace.instrumentation.freemarker;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class DollarVariableInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {
  static final String FREEMARKER_CORE = "freemarker.core";
  static final String ADVICE_BASE = FREEMARKER_CORE + ".DollarVariableDatadogAdvice$";

  public DollarVariableInstrumentation() {
    super("freemarker", "dollar-variable");
  }

  @Override
  public String instrumentedType() {
    return "freemarker.core.DollarVariable";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("accept")).and(takesArgument(0, named("freemarker.core.Environment"))),
        getClass().getName() + ADVICE_BASE + "DollarVariableAdvice");
  }
}
