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

/** TODO document this */
@AutoService(Instrumenter.class)
public class CompletableFutureUniCompletionInstrumentation extends Instrumenter.Default {
  static final String PACKAGE = "java.util.concurrent";
  static final String COMPLETABLE_FUTURE = PACKAGE + ".CompletableFuture";
  static final String UNI_COMPLETION = COMPLETABLE_FUTURE + "$UniCompletion";
  static final String ADVICE_BASE = PACKAGE + ".CompletableFutureAdvice$";

  public CompletableFutureUniCompletionInstrumentation() {
    super("java_concurrent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(UNI_COMPLETION);
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(UNI_COMPLETION, State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(isConstructor(), ADVICE_BASE + "UniConstructor");
  }
}
