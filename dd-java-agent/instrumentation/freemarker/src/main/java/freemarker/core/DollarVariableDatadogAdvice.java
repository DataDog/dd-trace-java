package freemarker.core;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import datadog.trace.instrumentation.freemarker.EnvironmentHelper;
import freemarker.template.TemplateHashModel;
import freemarker.template.TemplateModelException;
import freemarker.template.TemplateScalarModel;
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
      final String expression = DollarVariableHelper.fetchExpression(self);
      final TemplateHashModel templateHashModel = EnvironmentHelper.fetchRootDataModel(environment);
      String charSec = null;
      try {
        TemplateScalarModel templateScalarModel =
            (TemplateScalarModel) templateHashModel.get(expression);
        charSec = templateScalarModel.getAsString();
      } catch (TemplateModelException e) {
        log.debug("Failed to get DollarVariable templateModel", e);
        return;
      }
      final String templateName = environment.getMainTemplate().getName();
      final int line = self.beginLine;
      xssModule.onXss(charSec, templateName, line);
    }
  }
}
