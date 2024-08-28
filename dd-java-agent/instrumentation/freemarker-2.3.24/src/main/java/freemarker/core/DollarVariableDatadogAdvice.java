package freemarker.core;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import freemarker.template.TemplateException;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DollarVariableDatadogAdvice {

  protected static final Logger log = LoggerFactory.getLogger(DollarVariableDatadogAdvice.class);

  public static class DollarVariableAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.XSS)
    public static void onEnter(
        @Advice.Argument(0) final Environment environment, @Advice.This final DollarVariable self) {
      if (environment == null || self == null) {
        return;
      }
      final XssModule xssModule = InstrumentationBridge.XSS;
      if (xssModule == null) {
        return;
      }
      if (DollarVariableHelper.fetchAutoEscape(self)) {
        return;
      }
      String charSec = null;
      try {
        charSec = (String) self.calculateInterpolatedStringOrMarkup(environment);
      } catch (TemplateException e) {
        log.debug("Failed to get DollarVariable templateModel", e);
        return;
      }
      final String templateName = environment.getMainTemplate().getName();
      final int line = self.beginLine;
      xssModule.onXss(charSec, templateName, line);
    }
  }
}
