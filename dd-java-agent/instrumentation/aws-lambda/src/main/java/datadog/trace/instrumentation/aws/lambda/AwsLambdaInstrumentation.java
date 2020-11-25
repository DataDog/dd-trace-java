package datadog.trace.instrumentation.aws.lambda;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.lambda.AwsLambdaDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class AwsLambdaInstrumentation extends Instrumenter.Default {
  private final String handlerClass;
  private final String handlerFunction;

  public AwsLambdaInstrumentation() {
    super("aws-lambda");

    String handlerEnvironmentVariable = System.getenv("_HANDLER");

    if (handlerEnvironmentVariable == null) {
      handlerClass = null;
      handlerFunction = null;
    } else if (handlerEnvironmentVariable.indexOf('#') != -1) {
      int index = handlerEnvironmentVariable.indexOf('#');
      handlerClass = handlerEnvironmentVariable.substring(0, index);
      handlerFunction = handlerEnvironmentVariable.substring(index + 1);
    } else {
      handlerClass = handlerEnvironmentVariable;
      handlerFunction = null;
    }
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    if (handlerClass == null) {
      return none();
    } else {
      return named(handlerClass);
    }
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AwsLambdaDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    if (handlerFunction != null) {
      return singletonMap(
          isMethod().and(named(handlerFunction)),
          AwsLambdaInstrumentation.class.getName() + "$LambdaHandlerAdvice");
    } else {
      final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();

      transformers.put(
          isMethod()
              .and(named("handleRequest"))
              .and(takesArguments(2))
              .and(takesArgument(1, named("com.amazonaws.services.lambda.runtime.Context"))),
          AwsLambdaInstrumentation.class.getName() + "$LambdaHandlerAdvice");
      transformers.put(
          isMethod()
              .and(named("handleRequest"))
              .and(takesArguments(3))
              .and(takesArgument(0, named("java.io.InputStream")))
              .and(takesArgument(1, named("java.io.OutputStream")))
              .and(takesArgument(2, named("com.amazonaws.services.lambda.runtime.Context"))),
          AwsLambdaInstrumentation.class.getName() + "$LambdaHandlerAdvice");

      return transformers;
    }
  }

  public static class LambdaHandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope startMethod() {
      final AgentSpan span = startSpan("aws.lambda");

      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {

      if (scope == null) {
        return;
      }

      AgentSpan span = scope.span();
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);

      span.finish();
      scope.close();
    }
  }
}
