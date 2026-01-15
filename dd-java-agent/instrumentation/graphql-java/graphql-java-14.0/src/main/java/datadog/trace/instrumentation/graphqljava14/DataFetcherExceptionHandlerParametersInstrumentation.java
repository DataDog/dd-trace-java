package datadog.trace.instrumentation.graphqljava14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.graphqljava.AsyncExceptionUnwrapper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class DataFetcherExceptionHandlerParametersInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DataFetcherExceptionHandlerParametersInstrumentation() {
    super("graphql-java");
  }

  @Override
  public String instrumentedType() {
    return "graphql.execution.DataFetcherExceptionHandlerParameters";
  }

  // Safeguard copied from GraphQLJavaInstrumentation.java
  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // introduced in 20.0
    return not(hasClassNamed("graphql.execution.instrumentation.SimplePerformantInstrumentation"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getException")).and(returns(Throwable.class)),
        this.getClass().getName() + "$UnwrapGetExceptionAdvice");
  }

  public static class UnwrapGetExceptionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return(readOnly = false) Throwable throwable) {
      throwable = AsyncExceptionUnwrapper.unwrap(throwable);
    }
  }
}
