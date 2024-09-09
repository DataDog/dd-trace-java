package datadog.trace.instrumentation.freemarker9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import freemarker.core.DollarVariable2_3_9Helper;
import freemarker.core.Environment;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class DollarVariableInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {
  static final String FREEMARKER_CORE = "freemarker.core";

  public DollarVariableInstrumentation() {
    super("freemarker");
  }

  @Override
  public String muzzleDirective() {
    return "freemarker-2.3.9";
  }

  static final ElementMatcher.Junction<ClassLoader> NOT_VERSION_PRIOR_2_3_24 =
      not(hasClassNamed("freemarker.cache.ByteArrayTemplateLoader"));

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return NOT_VERSION_PRIOR_2_3_24;
  }

  @Override
  public String instrumentedType() {
    return FREEMARKER_CORE + ".DollarVariable";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {FREEMARKER_CORE + ".DollarVariable2_3_9Helper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("accept")
            .and(isMethod())
            .and(takesArgument(0, named(FREEMARKER_CORE + ".Environment"))),
        DollarVariableInstrumentation.class.getName() + "$DollarVariableAdvice");
  }

  public static class DollarVariableAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.XSS)
    public static void onEnter(
        @Advice.Argument(0) final Environment environment, @Advice.This final Object self) {
      if (environment == null || self == null) {
        return;
      }
      final XssModule xssModule = InstrumentationBridge.XSS;
      if (xssModule == null) {
        return;
      }
      String charSec = DollarVariable2_3_9Helper.fetchCharSec(self, environment);
      final String templateName = environment.getTemplate().getName();
      final int line = DollarVariable2_3_9Helper.fetchBeginLine(self);
      xssModule.onXss(charSec, templateName, line);
    }
  }
}
