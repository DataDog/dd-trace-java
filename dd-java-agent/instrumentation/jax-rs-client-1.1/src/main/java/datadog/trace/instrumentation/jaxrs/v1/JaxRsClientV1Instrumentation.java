package datadog.trace.instrumentation.jaxrs.v1;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jaxrs.v1.InjectAdapter.SETTER;
import static datadog.trace.instrumentation.jaxrs.v1.JaxRsClientV1Decorator.DECORATE;
import static datadog.trace.instrumentation.jaxrs.v1.JaxRsClientV1Decorator.JAX_RS_CLIENT_CALL;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.sun.jersey.api.client.ClientHandler;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JaxRsClientV1Instrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public JaxRsClientV1Instrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-client");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.sun.jersey.api.client.ClientHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JaxRsClientV1Decorator", packageName + ".InjectAdapter",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("handle")
            .and(takesArgument(0, extendsClass(named("com.sun.jersey.api.client.ClientRequest"))))
            .and(returns(extendsClass(named("com.sun.jersey.api.client.ClientResponse")))),
        JaxRsClientV1Instrumentation.class.getName() + "$HandleAdvice");
  }

  public static class HandleAdvice {

    @Advice.OnMethodEnter
    public static AgentScope onEnter(
        @Advice.Argument(value = 0) final ClientRequest request,
        @Advice.This final ClientHandler thisObj) {

      // WARNING: this might be a chain...so we only have to trace the first in the chain.
      final boolean isRootClientHandler = null == request.getProperties().get(DD_SPAN_ATTRIBUTE);
      if (isRootClientHandler) {
        final AgentSpan span = startSpan(JAX_RS_CLIENT_CALL);
        DECORATE.afterStart(span);
        DECORATE.onRequest(span, request);
        request.getProperties().put(DD_SPAN_ATTRIBUTE, span);

        propagate().inject(span, request.getHeaders(), SETTER);
        propagate()
            .injectPathwayContext(
                span, request.getHeaders(), SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);
        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final ClientResponse response,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      DECORATE.onResponse(span, response);
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      scope.close();
      scope.span().finish();
    }
  }
}
