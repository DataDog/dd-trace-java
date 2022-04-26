package datadog.trace.lambda;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
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

  private static final String START_INVOCATION = "/lambda/start-invocation";
  private static final String END_INVOCATION = "/lambda/end-invocation";

  private static final Long REQUEST_TIMEOUT_IN_S = 1L;

  private static OkHttpClient HTTP_CLIENT =
      new OkHttpClient.Builder()
          .retryOnConnectionFailure(true)
          .connectTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .writeTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .readTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .callTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .build();

  private static final MediaType jsonMediaType = MediaType.parse("application/json");
  private static final JsonAdapter<Object> adapter =
      new Moshi.Builder().build().adapter(Object.class);

  private static String EXTENSION_BASE_URL = "http://127.0.0.1:8124";

  public static DummyLambdaContext notifyStartInvocation(Object event) {
    RequestBody body = RequestBody.create(jsonMediaType, writeValueAsString(event));
    try (Response response =
        HTTP_CLIENT
            .newCall(
                new Request.Builder()
                    .url(EXTENSION_BASE_URL + START_INVOCATION)
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
      log.error("could not reach the extension");
    }
    return null;
  }

  public static boolean notifyEndInvocation(boolean isError) {
    RequestBody body = RequestBody.create(jsonMediaType, "{}");
    Request.Builder builder =
        new Request.Builder()
            .url(EXTENSION_BASE_URL + END_INVOCATION)
            .addHeader(DATADOG_META_LANG, "java")
            .post(body);
    if (isError) {
      builder.addHeader(DATADOG_INVOCATION_ERROR, "true");
    }
    try (Response response = HTTP_CLIENT.newCall(builder.build()).execute()) {
      if (response.isSuccessful()) {
        log.debug("notifyEndInvocation success");
        return true;
      }
    } catch (Exception e) {
      log.error("could not reach the extension, not injecting the context", e);
    }
    return false;
  }

  public static String writeValueAsString(Object obj) {
    String json = "{}";
    if (null != obj) {
      try {
        json = adapter.toJson(obj);
      } catch (Exception e) {
        log.error("could not write the value into a string", e);
      }
    }
    return json;
  }

  public static void setExtensionBaseUrl(String extensionBaseUrl) {
    EXTENSION_BASE_URL = extensionBaseUrl;
  }
}
