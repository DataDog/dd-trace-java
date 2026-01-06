package datadog.trace.instrumentation.thymeleaf;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class ElementTagStructureHandlerInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ElementTagStructureHandlerInstrumentation() {
    super("thymeleaf");
  }

  @Override
  public String instrumentedType() {
    return "org.thymeleaf.engine.ElementTagStructureHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {

    transformer.applyAdvice(
        isMethod().and(named("setBody")).and(takesArgument(0, CharSequence.class)),
        packageName + ".BodyAdvice");
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
}
