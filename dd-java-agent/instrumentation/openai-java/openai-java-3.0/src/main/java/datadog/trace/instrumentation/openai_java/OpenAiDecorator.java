package datadog.trace.instrumentation.openai_java;

import com.openai.core.ClientOptions;
import com.openai.core.http.Headers;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.api.telemetry.LLMObsMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.List;

public class OpenAiDecorator extends ClientDecorator {
  public static final OpenAiDecorator DECORATE = new OpenAiDecorator();

  private static final String INTEGRATION = "openai";
  private static final String INSTRUMENTATION_NAME = "openai-java";
  private static final CharSequence SPAN_NAME = UTF8BytesString.create("openai.request");

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create(INTEGRATION);

  private static final String METRIC_PREFIX = "openai.organization.ratelimit.";
  private static final String REQUESTS_LIMIT_METRIC = METRIC_PREFIX + "requests.limit";
  private static final String REQUESTS_REMAINING_METRIC = METRIC_PREFIX + "requests.remaining";
  private static final String TOKENS_LIMIT_METRIC = METRIC_PREFIX + "tokens.limit";
  private static final String TOKENS_REMAINING_METRIC = METRIC_PREFIX + "tokens.remaining";

  private static final String HEADER_PREFIX = "x-ratelimit-";
  private static final String LIMIT_REQUESTS_HEADER = HEADER_PREFIX + "limit-requests";
  private static final String REMAINING_REQUESTS_HEADER = HEADER_PREFIX + "remaining-requests";
  private static final String LIMIT_TOKENS_HEADER = HEADER_PREFIX + "limit-tokens";
  private static final String REMAINING_TOKENS_HEADER = HEADER_PREFIX + "remaining-tokens";

  private final boolean llmObsEnabled = Config.get().isLlmObsEnabled();
  private final WellKnownTags wellKnownTags = Config.get().getWellKnownTags();

  public AgentSpan startSpan(ClientOptions clientOptions) {
    AgentSpan span = AgentTracer.startSpan(INSTRUMENTATION_NAME, SPAN_NAME);
    afterStart(span);
    String baseUrl = clientOptions.baseUrl();
    span.setTag(CommonTags.OPENAI_API_BASE, baseUrl);
    span.setTag(CommonTags.MODEL_PROVIDER, detectProvider(baseUrl));
    span.setTag(CommonTags.OPENAI_REQUEST_METHOD, "POST");
    return span;
  }

  private String detectProvider(String baseUrl) {
    if (baseUrl != null) {
      String lower = baseUrl.toLowerCase();
      if (lower.contains("azure")) return "azure_openai";
      if (lower.contains("deepseek")) return "deepseek";
    }
    return "openai";
  }

  public void finishSpan(AgentSpan span, Throwable err) {
    try {
      if (err != null) {
        onError(span, err);
      }
      DECORATE.beforeFinish(span);
    } finally {
      span.finish();
    }
  }

  @Override
  protected String service() {
    return null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {INSTRUMENTATION_NAME};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.LLMOBS;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    if (llmObsEnabled) {
      // set UST (unified service tags, env, service, version)
      span.setTag(CommonTags.ENV, wellKnownTags.getEnv());
      span.setTag(CommonTags.SERVICE, wellKnownTags.getService());
      span.setTag(CommonTags.VERSION, wellKnownTags.getVersion());

      span.setTag(CommonTags.ML_APP, Config.get().getLlmObsMlApp());

      AgentSpanContext parent = LLMObsContext.current();
      String parentSpanId = LLMObsContext.ROOT_SPAN_ID;
      if (parent != null) {
        parentSpanId = String.valueOf(parent.getSpanId());
      }
      span.setTag(CommonTags.PARENT_ID, parentSpanId);
    }
    return super.afterStart(span);
  }

  @Override
  public AgentSpan beforeFinish(AgentSpan span) {
    if (llmObsEnabled) {
      Object spanKindTag = span.getTag(CommonTags.SPAN_KIND);
      if (spanKindTag != null) {
        String spanKind = spanKindTag.toString();
        boolean isRootSpan = span.getLocalRootSpan() == span;
        LLMObsMetricCollector.get()
            .recordSpanFinished(INTEGRATION, spanKind, isRootSpan, true, span.isError());
      }
    }
    return super.beforeFinish(span);
  }

  public void withHttpResponse(AgentSpan span, Headers headers) {
    if (!llmObsEnabled) {
      return;
    }
    List<String> values = headers.values("openai-organization");
    if (!values.isEmpty()) {
      span.setTag(CommonTags.OPENAI_ORGANIZATION, values.get(0));
    }
    setMetricFromHeader(span, REQUESTS_LIMIT_METRIC, headers, LIMIT_REQUESTS_HEADER);
    setMetricFromHeader(span, REQUESTS_REMAINING_METRIC, headers, REMAINING_REQUESTS_HEADER);
    setMetricFromHeader(span, TOKENS_LIMIT_METRIC, headers, LIMIT_TOKENS_HEADER);
    setMetricFromHeader(span, TOKENS_REMAINING_METRIC, headers, REMAINING_TOKENS_HEADER);
  }

  private static void setMetricFromHeader(
      AgentSpan span, String metric, Headers headers, String header) {
    List<String> values = headers.values(header);
    if (values.isEmpty()) {
      return;
    }
    String firstHeader = values.get(0);
    try {
      int value = Integer.parseInt(firstHeader);
      span.setMetric(metric, value);
    } catch (NumberFormatException ignore) {
    }
  }
}
