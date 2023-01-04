package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class KotlinCoroutinesInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public KotlinCoroutinesInstrumentation() {
    super("kotlin-coroutines");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ScopeStateCoroutineContext",
      packageName + ".ScopeStateCoroutineContext$ContextElementKey",
      packageName + ".CoroutineContextHelper",
    };
  }

  @Override
  public String instrumentedType() {
    return "kotlinx.coroutines.CoroutineContextKt";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("newCoroutineContext"))
            .and(takesArgument(1, named("kotlin.coroutines.CoroutineContext"))),
        packageName + ".CoroutineContextAdvice");
  }
}
