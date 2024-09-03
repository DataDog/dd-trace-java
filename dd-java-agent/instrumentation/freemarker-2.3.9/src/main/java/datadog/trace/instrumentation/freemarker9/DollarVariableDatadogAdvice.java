package datadog.trace.instrumentation.freemarker9;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import freemarker.core.DollarVariable9Helper;
import freemarker.core.Environment;
import net.bytebuddy.asm.Advice;

public final class DollarVariableDatadogAdvice {

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
      String charSec = DollarVariable9Helper.fetchCharSec(self, environment);
      final String templateName = environment.getTemplate().getName();
      final int line = DollarVariable9Helper.fetchBeginLine(self);
      xssModule.onXss(charSec, templateName, line);
    }
  }
}
