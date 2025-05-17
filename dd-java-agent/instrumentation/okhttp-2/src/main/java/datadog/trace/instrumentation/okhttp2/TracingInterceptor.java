package datadog.trace.instrumentation.okhttp2;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS;
import static datadog.trace.instrumentation.okhttp2.OkHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.okhttp2.OkHttpClientDecorator.OKHTTP_REQUEST;
import static datadog.trace.instrumentation.okhttp2.RequestBuilderInjectAdapter.SETTER;

import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import datadog.context.Context;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;

public class TracingInterceptor implements Interceptor {
  @Override
  public Response intercept(final Chain chain) throws IOException {
    final AgentSpan span = startSpan("okhttp", OKHTTP_REQUEST);

    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);

      DECORATE.onRequest(span, chain.request());

      final Request.Builder requestBuilder = chain.request().newBuilder();
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(CLIENT_PATHWAY_EDGE_TAGS);
      defaultPropagator().inject(Context.current().with(dsmContext), requestBuilder, SETTER);

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
