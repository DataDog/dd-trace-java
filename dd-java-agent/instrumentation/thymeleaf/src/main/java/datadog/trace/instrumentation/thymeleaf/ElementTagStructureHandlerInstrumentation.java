package datadog.trace.instrumentation.thymeleaf;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XssModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.engine.ElementTagStructureHandler;
import org.thymeleaf.processor.element.IElementTagStructureHandler;

@AutoService(Instrumenter.class)
public class ElementTagStructureHandlerInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public ElementTagStructureHandlerInstrumentation() {
    super("thymeleaf");
  }

  @Override
  public String instrumentedType() {
    return "org.thymeleaf.engine.ElementTagStructureHandler";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("setBody")).and(takesArgument(0, CharSequence.class)),
        ElementTagStructureHandlerInstrumentation.class.getName() + "$BodyAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.thymeleaf.processor.element.IElementTagStructureHandler",
        ThymeleafContext.class.getName());
  }

  public static class BodyAdvice {

    public static final Logger log = LoggerFactory.getLogger(BodyAdvice.class);

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.XSS)
    public static void setBody(
        @Advice.This ElementTagStructureHandler self, @Advice.Argument(0) final CharSequence text) {
      final XssModule module = InstrumentationBridge.XSS;
      if (module != null) {
        ContextStore<IElementTagStructureHandler, ThymeleafContext> contextStore =
            InstrumentationContext.get(IElementTagStructureHandler.class, ThymeleafContext.class);
        log.debug("Get contextStore {}", contextStore);
        log.debug("Try to get {} from contextStore", self);
        ThymeleafContext ctx = contextStore.get(self);
        log.debug("Get ThymeleafContext {}", ctx);
        if (ctx != null) {
          log.debug("Call module#onXss {} {} {}", text, ctx.getTemplateName(), ctx.getLine());
          module.onXss(text, ctx.getTemplateName(), ctx.getLine());
        }
      }
    }
  }
}
