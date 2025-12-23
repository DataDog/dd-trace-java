package datadog.trace.instrumentation.thymeleaf;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;

public class ProcessAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  @Propagation
  public static void doProcess(
      @Advice.Argument(1) final IProcessableElementTag tag,
      @Advice.Argument(4) final IElementTagStructureHandler handler) {
    if (InstrumentationBridge.XSS != null) {
      ContextStore<IElementTagStructureHandler, ThymeleafContext> contextStore =
          InstrumentationContext.get(IElementTagStructureHandler.class, ThymeleafContext.class);
      contextStore.put(handler, new ThymeleafContext(tag.getTemplateName(), tag.getLine()));
    }
  }
}
