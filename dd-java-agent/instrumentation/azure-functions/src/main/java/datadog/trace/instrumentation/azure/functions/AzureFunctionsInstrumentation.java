package datadog.trace.instrumentation.azure.functions;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.isAnnotatedWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.azure.functions.AzureFunctionsDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class AzureFunctionsInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public AzureFunctionsInstrumentation() {
    super("azure-functions");
  }

  @Override
  public String hierarchyMarkerType() {
    /*
    Due to the class-loading in the Azure Function environment we cannot assume that
    "com.microsoft.azure.functions.annotation.FunctionName" will be visible (as in defined as a
    resource) for any types using that annotation
    */
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return declaresMethod(
        isAnnotatedWith(named("com.microsoft.azure.functions.annotation.FunctionName")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AzureFunctionsDecorator", packageName + ".HttpRequestMessageExtractAdapter"
    };
  }

  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(takesArgument(0, named("com.microsoft.azure.functions.HttpRequestMessage")))
            .and(takesArgument(1, named("com.microsoft.azure.functions.ExecutionContext"))),
        AzureFunctionsInstrumentation.class.getName() + "$AzureFunctionsAdvice");
  }

  public static class AzureFunctionsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope methodEnter(
        @Advice.Argument(0) final HttpRequestMessage<?> request,
        @Advice.Argument(1) final ExecutionContext executionContext) {
      final Context parentContext = DECORATE.extract(request);
      final Context context = DECORATE.startSpan(request, parentContext);
      final AgentSpan span = fromContext(context);
      DECORATE.afterStart(span, executionContext.getFunctionName());
      DECORATE.onRequest(span, request, request, parentContext);
      HTTP_RESOURCE_DECORATOR.withRoute(
          span, request.getHttpMethod().name(), request.getUri().getPath());
      return context.attach();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final ContextScope scope,
        @Advice.Return final HttpResponseMessage response,
        @Advice.Thrown final Throwable throwable) {
      final AgentSpan span = fromContext(scope.context());
      DECORATE.onError(span, throwable);
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(scope.context());
      scope.close();
      span.finish();
    }
  }
}
