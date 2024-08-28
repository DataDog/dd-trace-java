package datadog.trace.instrumentation.freemarker;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
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
    super("freemarker");
  }

  @Override
  public String instrumentedType() {
    return FREEMARKER_CORE + ".DollarVariable";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      FREEMARKER_CORE + ".DollarVariableHelper", FREEMARKER_CORE + ".DollarVariableDatadogAdvice"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("accept")
            .and(isMethod())
            .and(takesArgument(0, named(FREEMARKER_CORE + ".Environment"))),
        ADVICE_BASE + "DollarVariableAdvice");
  }
}
