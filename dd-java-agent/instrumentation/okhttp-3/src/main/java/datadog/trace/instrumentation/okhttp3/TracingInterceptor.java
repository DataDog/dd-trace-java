package datadog.trace.instrumentation.okhttp3;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS;
import static datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator.OKHTTP_REQUEST;
import static datadog.trace.instrumentation.okhttp3.RequestBuilderInjectAdapter.SETTER;

import datadog.context.Context;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;

import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.core.scopemanager.ContinuableScopeManager;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class TracingInterceptor implements Interceptor {
  @Override
  public Response intercept(final Chain chain) throws IOException {
    if (chain.request().header("Datadog-Meta-Lang") != null) {
      return chain.proceed(chain.request());
    }

    System.out.println("before activateSpan: " + Context.current().getClass());
    final AgentSpan span = startSpan("okhttp", OKHTTP_REQUEST);
    try (final AgentScope scope = activateSpan(span)) {
      System.out.println("AgentScope: " + scope.getClass());
      System.out.println("scope.context(): " + scope.context().getClass());
      System.out.print("Context.current(): ");
      System.out.println(Context.current()==Context.root());
      System.out.println(Context.current().getClass());
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, chain.request());

      final Request.Builder requestBuilder = chain.request().newBuilder();
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(CLIENT_PATHWAY_EDGE_TAGS);

//     if(scope instanceof ContinuableScope){
//
//     }
      Context context = span.with(dsmContext);
      Baggage baggage = Baggage.fromContext(Context.current());
      if(baggage != null){
        System.out.println("Baggage: " + baggage.getW3cHeader());
      }else{
        System.out.println("null baggage");
      }
      System.out.println("span: " + span);
      System.out.println("span.with(baggage): " + span.with(baggage));
      defaultPropagator().inject(Context.current().with(span).with(dsmContext), requestBuilder, SETTER);

      final Response response;
      try {
        response = chain.proceed(requestBuilder.build());
      } catch (final Exception e) {
        DECORATE.onError(span, e);
        throw e;
      }

      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);
      return response;
    } finally {
      span.finish();
    }
  }
}
