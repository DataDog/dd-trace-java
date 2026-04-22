package datadog.trace.instrumentation.graphqljava14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import graphql.execution.ValueUnboxer;
import graphql.execution.instrumentation.Instrumentation;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class GraphQLJavaInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GraphQLJavaInstrumentation() {
    super("graphql-java");
  }

  @Override
  public String instrumentedType() {
    return "graphql.GraphQL";
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
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // introduced in 20.0
    return not(hasClassNamed("graphql.execution.instrumentation.SimplePerformantInstrumentation"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(
                namedOneOf(
                    "checkInstrumentationDefaultState" // 9.7+
                    // https://github.com/graphql-java/graphql-java/commit/821241de8ee055d6d254a9d95ef5143f9e540826
                    //                    "checkInstrumentation" // <9.7
                    // https://github.com/graphql-java/graphql-java/commit/78a6e4eda1c13f47573adb879ae781cce794e96a
                    ))
            .and(returns(named("graphql.execution.instrumentation.Instrumentation"))),
        this.getClass().getName() + "$AddInstrumentationAdvice");
  }

  @SuppressWarnings("unused")
  public static class AddInstrumentationAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return(readOnly = false) Instrumentation instrumentation) {
      instrumentation = GraphQLInstrumentation.install(instrumentation);
    }

    public static void muzzleCheck() {
      // Class introduced in 15.0
      ValueUnboxer value = ValueUnboxer.DEFAULT;
    }
  }
}
