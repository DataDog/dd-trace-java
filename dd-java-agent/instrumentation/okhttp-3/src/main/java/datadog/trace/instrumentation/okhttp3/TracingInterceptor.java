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
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class TracingInterceptor implements Interceptor {
  @Override
  public Response intercept(final Chain chain) throws IOException {
    if (chain.request().header("Datadog-Meta-Lang") != null) {
      return chain.proceed(chain.request());
    }

    final AgentSpan span = startSpan("okhttp", OKHTTP_REQUEST);

    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, chain.request());

      final Request.Builder requestBuilder = chain.request().newBuilder();
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(CLIENT_PATHWAY_EDGE_TAGS);
      defaultPropagator()
          .inject(Context.current().with(span).with(dsmContext), requestBuilder, SETTER);

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
