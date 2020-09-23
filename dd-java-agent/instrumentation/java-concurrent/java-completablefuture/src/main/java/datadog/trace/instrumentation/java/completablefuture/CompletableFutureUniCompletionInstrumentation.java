package datadog.trace.instrumentation.java.completablefuture;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** TODO document this */
@AutoService(Instrumenter.class)
public class CompletableFutureUniCompletionInstrumentation extends Instrumenter.Default {
  static final String JAVA_UTIL_CONCURRENT = "java.util.concurrent";
  static final String COMPLETABLE_FUTURE = JAVA_UTIL_CONCURRENT + ".CompletableFuture";
  static final String UNI_COMPLETION = COMPLETABLE_FUTURE + "$UniCompletion";
  static final String ADVICE_BASE = JAVA_UTIL_CONCURRENT + ".CompletableFutureAdvice$";
  private static final String UNI_COMPLETION_HELPER =
      "datadog.trace.instrumentation.java.completablefuture.UniCompletionHelper";
  static final String[] HELPER_CLASS_NAMES = {
    UNI_COMPLETION_HELPER,
    UNI_COMPLETION_HELPER + "$ClaimHolder",
    UNI_COMPLETION_HELPER + "$1", // The supplier for the thread local
  };

  public CompletableFutureUniCompletionInstrumentation() {
    super("java_concurrent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(UNI_COMPLETION);
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(UNI_COMPLETION, ConcurrentState.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return HELPER_CLASS_NAMES.clone();
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher.Junction<MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(isConstructor(), ADVICE_BASE + "UniConstructor");
    transformers.put(named("claim").and(takesArguments(0)), ADVICE_BASE + "UniClaim");
    return transformers;
  }
}
