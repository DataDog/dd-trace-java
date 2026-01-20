package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class GraphQLUnwrapExceptionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GraphQLUnwrapExceptionInstrumentation() {
    super("graphql-java");
  }

  @Override
  public String instrumentedType() {
    return "graphql.execution.DataFetcherExceptionHandlerParameters";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getException")).and(returns(Throwable.class)),
        this.getClass().getName() + "$UnwrapGetExceptionAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.graphqljava.AsyncExceptionUnwrapper"};
  }

  public static class UnwrapGetExceptionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return(readOnly = false) Throwable throwable) {
      throwable = AsyncExceptionUnwrapper.unwrap(throwable);
    }
  }
}
