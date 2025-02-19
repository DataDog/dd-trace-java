package datadog.trace.instrumentation.azurefunctions;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.isAnnotatedWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.azurefunctions.AzureFunctionsDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
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
    public static AgentScope methodEnter(
        @Advice.Argument(0) final HttpRequestMessage request,
        @Advice.Argument(1) final ExecutionContext context) {
      final AgentSpanContext.Extracted extractedContext = DECORATE.extract(request);
      final AgentSpan span = DECORATE.startSpan(request, extractedContext);
      DECORATE.afterStart(span, context.getFunctionName());
      DECORATE.onRequest(span, request, request, extractedContext);
      HTTP_RESOURCE_DECORATOR.withRoute(
          span, request.getHttpMethod().name(), request.getUri().getPath());
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final HttpResponseMessage response,
        @Advice.Thrown final Throwable throwable) {
      final AgentSpan span = scope.span();
      DECORATE.onError(span, throwable);
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
