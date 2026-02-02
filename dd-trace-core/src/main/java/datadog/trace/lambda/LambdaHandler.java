package datadog.trace.lambda;

import static datadog.http.client.HttpRequest.APPLICATION_JSON;
import static datadog.http.client.HttpRequest.CONTENT_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class communicates with the serverless extension on start invocation and on end invocation.
 * The extension is responsible to parse the context and create the invocation span. The tracer will
 * also create the span (to be dropped by the extension) so newly created spans will be parenting to
 * the right span.
 */
public class LambdaHandler {

  private static final Logger log = LoggerFactory.getLogger(LambdaHandler.class);

  // Note: this header is used to disable tracing for calls to the extension
  private static final String DATADOG_META_LANG = "Datadog-Meta-Lang";

  private static final String DATADOG_TRACE_ID = "x-datadog-trace-id";
  private static final String DATADOG_SPAN_ID = "x-datadog-span-id";
  private static final String DATADOG_SAMPLING_PRIORITY = "x-datadog-sampling-priority";
  private static final String DATADOG_INVOCATION_ERROR = "x-datadog-invocation-error";
  private static final String DATADOG_INVOCATION_ERROR_MSG = "x-datadog-invocation-error-msg";
  private static final String DATADOG_INVOCATION_ERROR_TYPE = "x-datadog-invocation-error-type";
  private static final String DATADOG_INVOCATION_ERROR_STACK = "x-datadog-invocation-error-stack";
  private static final String LAMBDA_RUNTIME_AWS_REQUEST_ID = "lambda-runtime-aws-request-id";

  private static final String START_INVOCATION = "/lambda/start-invocation";
  private static final String END_INVOCATION = "/lambda/end-invocation";

  private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(3_000L);
  private static final HttpClient HTTP_CLIENT = createHttpClient();

  private static HttpClient createHttpClient() {
    return HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
  }

  private static final JsonAdapter<Object> adapter =
      new Moshi.Builder()
          .add(ByteArrayInputStream.class, new ReadFromInputStreamJsonAdapter())
          .add(ByteArrayOutputStream.class, new ReadFromOutputStreamJsonAdapter())
          .add(SkipUnsupportedTypeJsonAdapter.newFactory())
          .build()
          .adapter(Object.class);

  private static String EXTENSION_BASE_URL = "http://127.0.0.1:8124";

  public static AgentSpanContext notifyStartInvocation(Object event, String lambdaRequestId) {
    HttpRequestBody body = HttpRequestBody.of(writeValueAsString(event));
    HttpUrl url = HttpUrl.parse(EXTENSION_BASE_URL + START_INVOCATION);
    HttpRequest request =
        HttpRequest.newBuilder()
            .url(url)
            .header(CONTENT_TYPE, APPLICATION_JSON)
            .addHeader(DATADOG_META_LANG, "java")
            .addHeader(LAMBDA_RUNTIME_AWS_REQUEST_ID, lambdaRequestId)
            .post(body)
            .build();
    try (HttpResponse response = HTTP_CLIENT.execute(request)) {
      if (response.isSuccessful()) {
        return extractContextAndGetSpanContext(
            response,
            (carrier, classifier) -> {
              for (String headerName : carrier.headerNames()) {
                classifier.accept(headerName, carrier.header(headerName));
              }
            });
      }
    } catch (Throwable ignored) {
      log.error("could not reach the extension");
    }
    return null;
  }

  public static boolean notifyEndInvocation(
      AgentSpan span, Object result, boolean isError, String lambdaRequestId) {
    if (null == span || null == span.getSamplingPriority()) {
      log.error(
          "could not notify the extension as the lambda span is null or no sampling priority has been found");
      return false;
    }
    HttpUrl url = HttpUrl.parse(EXTENSION_BASE_URL + END_INVOCATION);
    HttpRequestBody body = HttpRequestBody.of(writeValueAsString(result));
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .url(url)
            .header(CONTENT_TYPE, APPLICATION_JSON)
            .addHeader(DATADOG_TRACE_ID, span.getTraceId().toString())
            .addHeader(DATADOG_SPAN_ID, DDSpanId.toString(span.getSpanId()))
            .addHeader(DATADOG_SAMPLING_PRIORITY, span.getSamplingPriority().toString())
            .addHeader(DATADOG_META_LANG, "java")
            .addHeader(LAMBDA_RUNTIME_AWS_REQUEST_ID, lambdaRequestId)
            .post(body);

    Object errorMessage = span.getTag(DDTags.ERROR_MSG);
    if (errorMessage != null) {
      builder.addHeader(DATADOG_INVOCATION_ERROR_MSG, errorMessage.toString());
    }

    Object errorType = span.getTag(DDTags.ERROR_TYPE);
    if (errorType != null) {
      builder.addHeader(DATADOG_INVOCATION_ERROR_TYPE, errorType.toString());
    }

    Object errorStack = span.getTag(DDTags.ERROR_STACK);
    if (errorStack != null) {
      String encodedErrStack =
          Base64.getEncoder()
              .encodeToString(errorStack.toString().getBytes(StandardCharsets.UTF_8));
      builder.addHeader(DATADOG_INVOCATION_ERROR_STACK, encodedErrStack);
    }

    if (isError) {
      builder.addHeader(DATADOG_INVOCATION_ERROR, "true");
    }

    try (HttpResponse response = HTTP_CLIENT.execute(builder.build())) {
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
        log.debug("could not write the value into a string", e);
      }
    }
    return json;
  }

  public static void setExtensionBaseUrl(String extensionBaseUrl) {
    EXTENSION_BASE_URL = extensionBaseUrl;
  }
}
