package datadog.trace.instrumentation.freemarker24;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import freemarker.core.DollarVariable24Helper;
import freemarker.core.Environment;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DollarVariableDatadogAdvice {

  protected static final Logger log = LoggerFactory.getLogger(DollarVariableDatadogAdvice.class);

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
      if (DollarVariable24Helper.fetchAutoEscape(self)) {
        return;
      }
      String charSec = DollarVariable24Helper.fetchCharSec(self, environment);
      final String templateName = environment.getMainTemplate().getName();
      final int line = DollarVariable24Helper.fetchBeginLine(self);
      xssModule.onXss(charSec, templateName, line);
    }
  }
}
