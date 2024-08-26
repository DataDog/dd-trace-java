package datadog.trace.instrumentation.freemarker;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;

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
    return FREEMARKER_CORE + ".DollarVariable";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      FREEMARKER_CORE + ".DollarVariableHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        NameMatchers.named("accept")
            .and(isMethod())
            .and(takesArgument(0, named(FREEMARKER_CORE + ".Environment"))),
        ADVICE_BASE + "DollarVariableAdvice");
  }
}
