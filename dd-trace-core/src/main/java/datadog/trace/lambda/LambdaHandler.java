package datadog.trace.lambda;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
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
  private static final String DATADOG_TAGS_KEY = "x-datadog-tags";
  private static final String DATADOG_UPPER_64_TRACE_ID_TAG_KEY = "_dd.p.tid";

  private static final String START_INVOCATION = "/lambda/start-invocation";
  private static final String END_INVOCATION = "/lambda/end-invocation";

  private static final Long REQUEST_TIMEOUT_IN_S = 1L;
  private static final int MAX_IDLE_CONNECTIONS = 5;
  private static final Long KEEP_ALIVE_DURATION = 300L;

  private static OkHttpClient HTTP_CLIENT =
      new OkHttpClient.Builder()
          .retryOnConnectionFailure(true)
          .connectTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .writeTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .readTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .callTimeout(REQUEST_TIMEOUT_IN_S, SECONDS)
          .connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION, SECONDS))
          .build();

  private static final MediaType jsonMediaType = MediaType.parse("application/json");
  private static final JsonAdapter<Object> adapter =
      new Moshi.Builder()
          .add(ByteArrayInputStream.class, new ReadFromInputStreamJsonAdapter())
          .add(SkipUnsupportedTypeJsonAdapter.newFactory())
          .build()
          .adapter(Object.class);

  private static String EXTENSION_BASE_URL = "http://127.0.0.1:8124";

  public static AgentSpan.Context notifyStartInvocation(
      Object event, PropagationTags.Factory propagationTagsFactory) {
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
        final String traceIDLower64Long = response.headers().get(DATADOG_TRACE_ID);
        final String priority = response.headers().get(DATADOG_SAMPLING_PRIORITY);
        final String tags = response.headers().get(DATADOG_TAGS_KEY);
        final String traceIDUpper64BitHex = findUpper64BitTraceId(tags);
        DDTraceId traceId;
        if (null != traceIDUpper64BitHex && null != traceIDLower64Long) {
          long lower64Long = Long.parseUnsignedLong(traceIDLower64Long);
          String lower64Hex = Long.toHexString(lower64Long);
          String full128BitHex = traceIDUpper64BitHex + lower64Hex;
          traceId = DDTraceId.fromHex(full128BitHex);
        } else {
          traceId = DDTraceId.from(traceIDLower64Long);
        }
        if (null != traceIDLower64Long && null != priority) {
          int samplingPriority = PrioritySampling.UNSET;
          try {
            samplingPriority = Integer.parseInt(priority);
          } catch (final NumberFormatException ignored) {
            log.warn("could not read the sampling priority, defaulting to UNSET");
          }
          log.debug(
              "notifyStartInvocation success, found traceID = {} and samplingPriority = {}",
              traceIDLower64Long,
              samplingPriority);
          PropagationTags propagationTags =
              propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, tags);
          return new ExtractedContext(
              traceId, DDSpanId.ZERO, samplingPriority, null, propagationTags, DATADOG);
        } else {
          log.debug(
              "could not find traceID or sampling priority in notifyStartInvocation, not injecting the context");
        }
      }
    } catch (Throwable ignored) {
      log.error("could not reach the extension");
    }
    return null;
  }

  private static String findUpper64BitTraceId(String tags)
      throws NumberFormatException, IndexOutOfBoundsException {
    if (tags == null) {
      return null;
    }
    String[] tagPairs = tags.split(",");
    for (String tagPair : tagPairs) {
      String[] tag = tagPair.trim().split("=");
      if (tag.length == 2 && tag[0].equals(DATADOG_UPPER_64_TRACE_ID_TAG_KEY)) {
        return tag[1];
      }
    }
    return null;
  }

  public static boolean notifyEndInvocation(AgentSpan span, Object result, boolean isError) {

    if (null == span || null == span.getSamplingPriority()) {
      log.error(
          "could not notify the extension as the lambda span is null or no sampling priority has been found");
      return false;
    }
    RequestBody body = RequestBody.create(jsonMediaType, writeValueAsString(result));
    Request.Builder builder =
        new Request.Builder()
            .url(EXTENSION_BASE_URL + END_INVOCATION)
            .addHeader(DATADOG_TRACE_ID, span.getTraceId().toString())
            .addHeader(DATADOG_SPAN_ID, DDSpanId.toString(span.getSpanId()))
            .addHeader(DATADOG_SAMPLING_PRIORITY, span.getSamplingPriority().toString())
            .addHeader(DATADOG_META_LANG, "java")
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
        log.debug("could not write the value into a string", e);
      }
    }
    return json;
  }

  public static void setExtensionBaseUrl(String extensionBaseUrl) {
    EXTENSION_BASE_URL = extensionBaseUrl;
  }
}
