package datadog.trace.instrumentation.java.completablefuture;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class CompletableFutureCompletionInstrumentation extends Instrumenter.Default {

  public CompletableFutureCompletionInstrumentation() {
    super("java_concurrent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("java.util.concurrent.CompletableFuture$Completion");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(isConstructor(), "java.util.concurrent.CompletionConstructorAdvice");
  }
}
