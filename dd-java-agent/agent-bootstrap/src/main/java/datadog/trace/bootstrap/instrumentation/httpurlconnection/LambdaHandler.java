package datadog.trace.bootstrap.instrumentation.httpurlconnection;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import datadog.trace.bootstrap.instrumentation.api.DummyLambdaContext;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LambdaHandler {

  private static final Logger log = LoggerFactory.getLogger(LambdaHandler.class);

  // Note: this header is used to disable tracing for calls to the extension
  private static final String DATADOG_META_LANG = "Datadog-Meta-Lang";

  private static final String DATADOG_TRACE_ID = "x-datadog-trace-id";
  private static final String DATADOG_SPAN_ID = "x-datadog-span-id";
  private static final String DATADOG_INVOCATION_ERROR = "x-datadog-invocation-error";

  private static final String START_INVOCATION = "http://127.0.0.1:8124/lambda/start-invocation";
  private static final String END_INVOCATION = "http://127.0.0.1:8124/lambda/end-invocation";
  private static final String FLUSH_INVOCATION = "http://127.0.0.1:8124/lambda/flush";

  private static final Long REQUEST_TIMEOUT_IN_S = 1L;
  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private static OkHttpClient httpClient =
      new OkHttpClient.Builder()
          .retryOnConnectionFailure(true)
          .connectTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .writeTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .readTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .callTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .build();

  public static DummyLambdaContext notifyStartInvocation(Object obj) {
    RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, writeValueAsString(obj));
    try (Response response =
        httpClient
            .newCall(
                new Request.Builder()
                    .url(START_INVOCATION)
                    .addHeader(DATADOG_META_LANG, "java")
                    .post(body)
                    .build())
            .execute()) {
      if (response.isSuccessful()) {
        final String traceID = response.headers().get(DATADOG_TRACE_ID);
        final String spanID = response.headers().get(DATADOG_SPAN_ID);
        if (null != traceID && null != spanID) {
          log.debug(
              "notifyStartInvocation success, found traceID = {} and spanID = {}", traceID, spanID);
          return new DummyLambdaContext(traceID, spanID);
        } else {
          log.error(
              "could not find traceID/spanID in notifyStartInvocation, not injecting the context");
        }
      }
    } catch (Throwable ignored) {
      log.error("could not reach the extension, not injecting the context");
    }
    return null;
  }

  public static void notifyEndInvocation(boolean isError) {
    RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, "{}");
    Request.Builder builder =
        new Request.Builder().url(END_INVOCATION).addHeader(DATADOG_META_LANG, "java").post(body);
    if (isError) {
      builder.addHeader(DATADOG_INVOCATION_ERROR, "true");
    }
    try (Response response = httpClient.newCall(builder.build()).execute()) {
      if (response.isSuccessful()) {
        log.debug("notifyEndInvocation success");
      }
    } catch (Exception e) {
      log.error("could not reach the extension, not injecting the context", e);
    }
  }

  public static String writeValueAsString(Object obj) {
    String json = "{}";
    if (null != obj) {
      try {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        json = mapper.writeValueAsString(obj);
        return json;
      } catch (Exception e) {
        log.error("could not write the value into a string", e);
      }
    }
    return json;
  }
}
