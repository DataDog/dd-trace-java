package datadog.trace.instrumentation.okhttp3;

import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.api.AgentSpan.Context;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator.NETWORK_DECORATE;
import static datadog.trace.instrumentation.okhttp3.RequestBuilderInjectAdapter.SETTER;

public class TracingCallFactory implements Call.Factory {

  private final OkHttpClient okHttpClient;

  public TracingCallFactory(final OkHttpClient okHttpClient) {
    this.okHttpClient = okHttpClient;
  }

  @Override
  public Call newCall(final Request request) {
    final AgentSpan span = startSpan("okhttp.http");
    try (final AgentScope scope = activateSpan(span, false)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);

      /** In case of exception network interceptor is not called */
      final OkHttpClient.Builder okBuilder = okHttpClient.newBuilder();
      okBuilder.networkInterceptors().add(0, new NetworkInterceptor(span.context()));

      okBuilder
        .interceptors()
        .add(
          0,
          new Interceptor() {
            @Override
            public Response intercept(final Chain chain) throws IOException {
              try (final AgentScope interceptorScope = activateSpan(span, false)) {
                return chain.proceed(chain.request());
              } catch (final Exception ex) {
                DECORATE.onError(scope, ex);
                throw ex;
              } finally {
                DECORATE.beforeFinish(span);
                span.finish();
              }
            }
          });
      return okBuilder.build().newCall(request);
    } catch (final Throwable ex) {
      // Not sure what would cause it to get here.
      DECORATE.onError(span, ex);
      throw new RuntimeException(ex);
    }
  }

  @Slf4j
  static class NetworkInterceptor implements Interceptor {
    public Context parentContext;

    NetworkInterceptor(final Context spanContext) {
      parentContext = spanContext;
    }


    @Override
    public Response intercept(final Chain chain) throws IOException {
      //rather than have original interceptor pull span and context and create span out of that, maybe we can
      //just have the network interceptor span pull the information from the current parent span, basically skip the
      //creation of the first interceptor.
      log.error("the parentContext in the TracingCallFactory is " + parentContext.toString());
      final AgentSpan networkSpan = startSpan("okhttp.http", parentContext);
      try (final AgentScope networkScope = activateSpan(networkSpan, true)) {
        NETWORK_DECORATE.afterStart(networkSpan);
        NETWORK_DECORATE.onRequest(networkSpan, chain.request());
        NETWORK_DECORATE.onPeerConnection(
          networkSpan, chain.connection().socket().getInetAddress());

        final Request.Builder requestBuilder = chain.request().newBuilder();
        propagate().inject(networkSpan, requestBuilder, SETTER);

        final Response response;
        try {
          response = chain.proceed(requestBuilder.build());
        } catch (final Exception e) {
          NETWORK_DECORATE.onError(networkScope, e);
          throw e;
        }

        NETWORK_DECORATE.onResponse(networkSpan, response);
        NETWORK_DECORATE.beforeFinish(networkScope);
        return response;
      }
    }
  }
}
