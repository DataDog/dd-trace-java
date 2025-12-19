package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.appsec.HttpClientPayload;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.appsec.HttpClientResponse;
import datadog.trace.api.appsec.MediaType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecInterceptor implements Interceptor {

  private static final int BODY_PARSING_SIZE_LIMIT = Config.get().getAppSecBodyParsingSizeLimit();

  private static final Logger LOGGER = LoggerFactory.getLogger(AppSecInterceptor.class);

  @Override
  public Response intercept(final Chain chain) throws IOException {
    try {
      final AgentSpan span = AgentTracer.activeSpan();
      final RequestContext ctx = span == null ? null : span.getRequestContext();
      if (ctx == null) {
        return chain.proceed(chain.request());
      }
      final long requestId = span.getSpanId();
      final boolean sampled = sampleRequest(ctx, requestId);
      final String url = span.getTag(Tags.HTTP_URL).toString();
      final Request request = onRequest(span, sampled, url, chain.request());
      final Response response = chain.proceed(request);
      return onResponse(span, sampled, response);
    } catch (final BlockingException e) {
      throw e;
    } catch (final Exception e) {
      LOGGER.debug("Failed to intercept request", e);
      return chain.proceed(chain.request());
    }
  }

  public static Request onRequest(
      final AgentSpan span, final boolean sampled, final String url, final Request request) {
    Request result = request;
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, HttpClientRequest, Flow<Void>> requestCb =
        cbp.getCallback(EVENTS.httpClientRequest());
    if (requestCb == null) {
      return request;
    }

    final RequestBody requestBody = request.body();
    final RequestContext ctx = span.getRequestContext();
    final long requestId = span.getSpanId();
    final HttpClientRequest clientRequest =
        new HttpClientRequest(requestId, url, request.method(), mapHeaders(request.headers()));
    if (sampled && requestBody != null) {
      // we are going to effectively read all the request body in memory to be analyzed by the WAF,
      // we also modify the outbound request accordingly
      final MediaType mediaType = contentType(requestBody);
      try {
        final long contentLength = requestBody.contentLength();
        if (shouldProcessBody(contentLength, mediaType)) {
          final byte[] payload = readBody(requestBody, (int) contentLength);
          if (payload.length <= BODY_PARSING_SIZE_LIMIT) {
            clientRequest.setBody(mediaType, new ByteArrayInputStream(payload));
          }
          result =
              request
                  .newBuilder()
                  .method(request.method(), RequestBody.create(requestBody.contentType(), payload))
                  .build(); // update request
        }
      } catch (IOException e) {
        // ignore it and keep the original request
      }
    }
    publish(ctx, clientRequest, requestCb);
    return result;
  }

  public static Response onResponse(
      final AgentSpan span, final boolean sampled, final Response response) {
    Response result = response;
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, HttpClientResponse, Flow<Void>> responseCb =
        cbp.getCallback(EVENTS.httpClientResponse());
    if (responseCb == null) {
      return response;
    }
    final ResponseBody responseBody = response.body();
    final RequestContext ctx = span.getRequestContext();
    final long requestId = span.getSpanId();
    final HttpClientResponse clientResponse =
        new HttpClientResponse(requestId, response.code(), mapHeaders(response.headers()));
    if (sampled && responseBody != null) {
      // we are going to effectively read all the response body in memory to be analyzed by the WAF,
      // we also
      // modify the inbound response accordingly
      final MediaType mediaType = contentType(responseBody);
      try {
        final long contentLength = responseBody.contentLength();
        if (shouldProcessBody(contentLength, mediaType)) {
          final byte[] payload = readBody(responseBody, (int) contentLength);
          if (payload.length <= BODY_PARSING_SIZE_LIMIT) {
            clientResponse.setBody(mediaType, new ByteArrayInputStream(payload));
          }
          result =
              response
                  .newBuilder()
                  .body(ResponseBody.create(responseBody.contentType(), payload))
                  .build();
        }
      } catch (IOException e) {
        // ignore it and keep the original response
      }
    }

    publish(ctx, clientResponse, responseCb);
    return result;
  }

  private static <P extends HttpClientPayload> void publish(
      final RequestContext ctx,
      final P request,
      final BiFunction<RequestContext, P, Flow<Void>> callback) {
    Flow<Void> flow = callback.apply(ctx, request);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction brf = ctx.getBlockResponseFunction();
      if (brf != null) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
      }
      throw new BlockingException("Blocked request (for http downstream request)");
    }
  }

  static boolean sampleRequest(final RequestContext ctx, final long requestId) {
    //  Check if the current http request was sampled
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Long, Flow<Boolean>> samplingCb =
        cbp.getCallback(EVENTS.httpClientSampling());
    if (samplingCb == null) {
      return false;
    }
    final Flow<Boolean> sampled = samplingCb.apply(ctx, requestId);
    return sampled.getResult() != null && sampled.getResult();
  }

  /**
   * Ensure we are only consuming payloads we can safely deserialize with a bounded size to prevent
   * from OOM
   */
  private static boolean shouldProcessBody(final long contentLength, final MediaType mediaType) {
    if (contentLength <= 0) {
      return false; // prevent from copying from unbounded source (just to be safe)
    }
    if (BODY_PARSING_SIZE_LIMIT <= 0) {
      return false; // effectively disabled by configuration
    }
    if (contentLength > BODY_PARSING_SIZE_LIMIT) {
      return false;
    }
    return mediaType.isDeserializable();
  }

  private static byte[] readBody(final RequestBody body, final int contentLength)
      throws IOException {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream(contentLength);
    try (final BufferedSink sink = Okio.buffer(Okio.sink(buffer))) {
      body.writeTo(sink);
    }
    return buffer.toByteArray();
  }

  private static byte[] readBody(final ResponseBody body, final int contentLength)
      throws IOException {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream(contentLength);
    try (final BufferedSource source = body.source();
        final Sink sink = Okio.sink(buffer)) {
      source.readAll(sink);
    }
    return buffer.toByteArray();
  }

  private static Map<String, List<String>> mapHeaders(final Headers headers) {
    if (headers == null) {
      return Collections.emptyMap();
    }
    final Map<String, List<String>> result = new HashMap<>(headers.size());
    for (final String name : headers.names()) {
      result.put(name, headers.values(name));
    }
    return result;
  }

  private static MediaType contentType(final RequestBody body) {
    return MediaType.parse(
        body == null || body.contentType() == null ? null : body.contentType().toString());
  }

  private static MediaType contentType(final ResponseBody body) {
    return MediaType.parse(
        body == null || body.contentType() == null ? null : body.contentType().toString());
  }
}
