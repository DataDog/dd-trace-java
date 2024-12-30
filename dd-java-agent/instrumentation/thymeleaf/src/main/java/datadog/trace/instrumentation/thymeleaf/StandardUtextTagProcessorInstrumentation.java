package datadog.trace.instrumentation.thymeleaf;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class StandardUtextTagProcessorInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public StandardUtextTagProcessorInstrumentation() {
    super("thymeleaf");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isMethod().and(named("doProcess")), packageName + ".ProcessAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ThymeleafContext"};
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.thymeleaf.processor.element.IElementTagStructureHandler",
        "datadog.trace.instrumentation.thymeleaf.ThymeleafContext");
  }

  @Override
  public String instrumentedType() {
    return "org.thymeleaf.standard.processor.StandardUtextTagProcessor";
  }
}
