package datadog.trace.instrumentation.graphqljava20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import graphql.execution.instrumentation.Instrumentation;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class GraphQLJavaInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GraphQLJavaInstrumentation() {
    super("graphql-java");
  }

  @Override
  public String instrumentedType() {
    return "graphql.GraphQL$Builder";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.graphqljava.GraphQLDecorator",
      "datadog.trace.instrumentation.graphqljava.ParsingInstrumentationContext",
      "datadog.trace.instrumentation.graphqljava.ExecutionInstrumentationContext",
      "datadog.trace.instrumentation.graphqljava.ValidationInstrumentationContext",
      "datadog.trace.instrumentation.graphqljava.State",
      packageName + ".GraphQLInstrumentation",
      "datadog.trace.instrumentation.graphqljava.GraphQLQuerySanitizer",
      "datadog.trace.instrumentation.graphqljava.InstrumentedDataFetcher",
      "datadog.trace.instrumentation.graphqljava.AsyncExceptionUnwrapper"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("build")).and(returns(named("graphql.GraphQL"))),
        this.getClass().getName() + "$AddInstrumentationAdvice");
  }

  @SuppressWarnings("unused")
  public static class AddInstrumentationAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.FieldValue(value = "instrumentation", readOnly = false)
            Instrumentation instrumentation) {
      instrumentation = GraphQLInstrumentation.install(instrumentation);
    }
  }
}
