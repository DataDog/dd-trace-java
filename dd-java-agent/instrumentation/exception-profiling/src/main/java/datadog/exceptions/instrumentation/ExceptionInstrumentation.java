package datadog.exceptions.instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
@Slf4j
public class ExceptionInstrumentation extends Instrumenter.Default {
  public ExceptionInstrumentation() {
    super("exceptions");
  }

  @Override
  protected boolean defaultEnabled() {
    return true;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return ElementMatchers.is(Exception.class);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isConstructor(), "datadog.exceptions.instrumentation.ExceptionAdvice");
  }
}
