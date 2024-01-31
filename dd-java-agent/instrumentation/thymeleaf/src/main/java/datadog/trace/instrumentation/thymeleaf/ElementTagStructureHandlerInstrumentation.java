package datadog.trace.instrumentation.thymeleaf;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;

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
