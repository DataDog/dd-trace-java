package datadog.trace.instrumentation.thymeleaf;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.thymeleaf.engine.ElementTagStructureHandler;
import org.thymeleaf.processor.element.IElementTagStructureHandler;

public class BodyAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Sink(VulnerabilityTypes.XSS)
  public static void setBody(
      @Advice.This ElementTagStructureHandler self, @Advice.Argument(0) final CharSequence text) {
    final XssModule module = InstrumentationBridge.XSS;
    if (module != null) {
      ContextStore<IElementTagStructureHandler, ThymeleafContext> contextStore =
          InstrumentationContext.get(IElementTagStructureHandler.class, ThymeleafContext.class);
      ThymeleafContext ctx = contextStore.get(self);
      if (ctx != null) {
        module.onXss(text, ctx.getTemplateName(), ctx.getLine());
      }
    }
  }
}
