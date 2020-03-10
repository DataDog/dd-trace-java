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
  public String[] helperClassNames() {
    /*
     * Since the only instrumentation target is java.lang.Exception which is loaded by bootstrap classloader
     * it is ok to use helper classes instead of hacking around a Java 8 specific bootstrap.
     */
    return new String[] {
      "com.datadog.profiling.exceptions.AdaptiveIntervalSampler",
      "com.datadog.profiling.exceptions.ExceptionCountEvent",
      "com.datadog.profiling.exceptions.ExceptionHistogram",
      "com.datadog.profiling.exceptions.ExceptionHistogram$1",
      "com.datadog.profiling.exceptions.ExceptionHistogram$ValueVisitor",
      "com.datadog.profiling.exceptions.ExceptionProfiling",
      "com.datadog.profiling.exceptions.ExceptionProfiling$1",
      "com.datadog.profiling.exceptions.ExceptionProfiling$Singleton",
      "com.datadog.profiling.exceptions.ExceptionSampleEvent",
      "com.datadog.profiling.exceptions.ExceptionSampler"
    };
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
