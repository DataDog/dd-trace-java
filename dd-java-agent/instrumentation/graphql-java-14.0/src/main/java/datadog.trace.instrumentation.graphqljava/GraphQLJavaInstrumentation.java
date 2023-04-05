package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import graphql.execution.ValueUnboxer;
import graphql.execution.instrumentation.Instrumentation;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class GraphQLJavaInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
      packageName + ".GraphQLDecorator",
      packageName + ".ParsingInstrumentationContext",
      packageName + ".ExecutionInstrumentationContext",
      packageName + ".ValidationInstrumentationContext",
      packageName + ".GraphQLInstrumentation$State",
      packageName + ".GraphQLInstrumentation",
      packageName + ".GraphQLQuerySanitizer",
      packageName + ".InstrumentedDataFetcher"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
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
