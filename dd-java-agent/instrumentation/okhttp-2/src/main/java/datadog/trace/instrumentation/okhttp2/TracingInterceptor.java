package datadog.trace.instrumentation.okhttp2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.okhttp2.OkHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.okhttp2.OkHttpClientDecorator.OKHTTP_REQUEST;
import static datadog.trace.instrumentation.okhttp2.RequestBuilderInjectAdapter.SETTER;

import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.io.IOException;

public class TracingInterceptor implements Interceptor {
  @Override
  public Response intercept(final Chain chain) throws IOException {
    final AgentSpan span = startSpan(OKHTTP_REQUEST);

    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);

      DECORATE.onRequest(span, chain.request());

      final Request.Builder requestBuilder = chain.request().newBuilder();
      propagate().inject(span, requestBuilder, SETTER);
      propagate()
          .injectPathwayContext(
              span, requestBuilder, SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);

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
