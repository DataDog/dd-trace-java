package freemarker.core;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DollarVariable9DatadogAdvice {

  protected static final Logger log = LoggerFactory.getLogger(DollarVariable9DatadogAdvice.class);

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
      final Expression expression = DollarVariable9Helper.fetchEscapeExpression(self);
      if (expression instanceof BuiltIn) {
        return;
      }
      String charSec = null;
      try {
        charSec = environment.getDataModel().get(expression.toString()).toString();
      } catch (Exception e) {
        log.debug("Failed to get data model", e);
        return;
      }
      final String templateName = environment.getTemplate().getName();
      final int line = self.beginLine;
      xssModule.onXss(charSec, templateName, line);
    }
  }
}
