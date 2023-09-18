package datadog.trace.instrumentation.thymeleaf;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;

@AutoService(Instrumenter.class)
public class StandardUtextTagProcessorInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public StandardUtextTagProcessorInstrumentation() {
    super("thymeleaf");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("doProcess"))
            .and(takesArgument(0, ITemplateContext.class))
            .and(takesArgument(1, IProcessableElementTag.class))
            .and(takesArgument(2, AttributeName.class))
            .and(takesArgument(3, String.class))
            .and(takesArgument(4, IElementTagStructureHandler.class)),
        StandardUtextTagProcessorInstrumentation.class.getName() + "$ProcessAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.thymeleaf.processor.element.IElementTagStructureHandler",
        ThymeleafContext.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "org.thymeleaf.standard.processor.StandardUtextTagProcessor";
  }

  public static class ProcessAdvice {

    public static final Logger log = LoggerFactory.getLogger(ProcessAdvice.class);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Propagation
    public static void doProcess(
        @Advice.Argument(1) final IProcessableElementTag tag,
        @Advice.Argument(4) final IElementTagStructureHandler handler) {
      if (InstrumentationBridge.XSS != null) {
        ContextStore<IElementTagStructureHandler, ThymeleafContext> contextStore =
            InstrumentationContext.get(IElementTagStructureHandler.class, ThymeleafContext.class);
        log.debug("ContextStore {}", contextStore);
        contextStore.put(handler, new ThymeleafContext(tag.getTemplateName(), tag.getLine()));
        log.debug(
            "{} {} {} added on contextStore {}",
            handler,
            tag.getTemplateName(),
            tag.getLine(),
            contextStore);
      }
    }
  }
}
