package datadog.trace.instrumentation.openai_java;

import static datadog.trace.api.llmobs.GenAiApmTags.stringTag;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import com.openai.core.ClientOptions;
import com.openai.core.http.Headers;
import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceApiInfo;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.GenAiApmTags;
import datadog.trace.api.llmobs.LLMObsParent;
import datadog.trace.api.llmobs.LLMObsSampler;
import datadog.trace.api.telemetry.LLMObsMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

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

  private static final String EMBEDDINGS_ENDPOINT = "/v1/embeddings";

  private static final String HEADER_PREFIX = "x-ratelimit-";
  private static final String LIMIT_REQUESTS_HEADER = HEADER_PREFIX + "limit-requests";
  private static final String REMAINING_REQUESTS_HEADER = HEADER_PREFIX + "remaining-requests";
  private static final String LIMIT_TOKENS_HEADER = HEADER_PREFIX + "limit-tokens";
  private static final String REMAINING_TOKENS_HEADER = HEADER_PREFIX + "remaining-tokens";

  private final boolean llmObsEnabled = Config.get().isLlmObsEnabled();
  private final WellKnownTags wellKnownTags = Config.get().getWellKnownTags();
  private final LLMObsSampler sampler = LLMObsSampler.fromConfig();

  public AgentSpan startSpan(ClientOptions clientOptions) {
    AgentSpan span = AgentTracer.startSpan(INTEGRATION, SPAN_NAME);
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
    onError(span, err);
    beforeFinish(span);
    span.finish();
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
  protected void doAfterStart(@Nonnull AgentSpan span) {
    if (llmObsEnabled) {
      // set global dd_tags as base layer so UST and span-level tags can override them
      for (Map.Entry<String, String> entry : Config.get().getGlobalTags().entrySet()) {
        span.setTag(CommonTags.TAG_PREFIX + entry.getKey(), entry.getValue());
      }

      // set UST (unified service tags, env, service, version)
      span.setTag(CommonTags.ENV, wellKnownTags.getEnv());
      span.setTag(CommonTags.SERVICE, wellKnownTags.getService());
      span.setTag(CommonTags.VERSION, wellKnownTags.getVersion());
      span.setTag(CommonTags.DDTRACE_VERSION, DDTraceApiInfo.VERSION);

      span.setTag(CommonTags.ML_APP, Config.get().getLlmObsMlApp());
      span.setTag(CommonTags.SOURCE, "integration");
      span.setTag(CommonTags.INTEGRATION, INTEGRATION);

      // Resolve the LLMObs parent the same way DDLLMObsSpan does: the in-process context when it
      // is on this trace, else what arrived from another service (an HTTP handler or queue
      // consumer calling OpenAI without an LLMObs span of its own), else none. Inheriting
      // session_id is what files this span under its session in the LLM Trace Explorer.
      LLMObsParent parent = LLMObsParent.resolve(span);
      span.setTag(CommonTags.PARENT_ID, parent.parentId);
      // With no trace id to inherit the tag is left unset, and the mapper seeds it from the APM
      // trace id the same way DDLLMObsSpan does.
      if (parent.traceId != null) {
        span.setTag(CommonTags.TRACE_ID, parent.traceId);
      }
      if (parent.mlApp != null) {
        span.setTag(CommonTags.ML_APP, parent.mlApp);
      }
      if (parent.sessionId != null) {
        span.setTag(CommonTags.SESSION_ID, parent.sessionId);
      }
      if (parent.agentVersion != null) {
        span.setTag(CommonTags.AGENT_VERSION, parent.agentVersion);
      }
      // The name is only meaningful alongside an id.
      if (parent.parentAgentSpanId != null) {
        span.setTag(CommonTags.PAGENT_SPAN_ID, parent.parentAgentSpanId);
        if (parent.parentAgentName != null) {
          span.setTag(CommonTags.PAGENT_NAME, parent.parentAgentName);
        }
      }

      String sampleRate = parent.sampleRate;
      String samplingDecision = parent.samplingDecision;
      if (parent.isRoot()) {
        sampleRate = sampler.formattedRate();
        samplingDecision = sampler.decide(span.getTraceId());
      }
      if (samplingDecision != null && sampleRate != null) {
        span.setTag(CommonTags.SAMPLING_DECISION, samplingDecision);
        span.setTag(CommonTags.SAMPLE_RATE, sampleRate);
      }
    }
    super.doAfterStart(span);
  }

  @Override
  protected void doBeforeFinish(@Nonnull Context context) {
    AgentSpan span = fromContext(context);
    if (llmObsEnabled && span != null) {
      span.setTag(CommonTags.ERROR, span.isError() ? 1 : 0);
      span.setTag(CommonTags.ERROR_TYPE, span.getTag(DDTags.ERROR_TYPE));

      GenAiApmTags.apply(span);

      Object spanKindTag = span.getTag(CommonTags.SPAN_KIND);
      if (spanKindTag != null) {
        String spanKind = spanKindTag.toString();
        boolean isRootSpan = span.getLocalRootSpan() == span;
        LLMObsMetricCollector.get()
            .recordSpanFinished(INTEGRATION, spanKind, isRootSpan, true, span.isError(), false);
      }
    } else if (span != null) {
      // Tracing still runs with LLM Observability off, where these remain resolvable.
      GenAiApmTags.apply(
          span, operationName(span), requestedModel(span), Config.get().getLlmObsMlApp());
    }
    super.doBeforeFinish(context);
  }

  private static String operationName(AgentSpan span) {
    String endpoint = stringTag(span, CommonTags.OPENAI_REQUEST_ENDPOINT);
    if (endpoint == null) {
      return null;
    }
    return EMBEDDINGS_ENDPOINT.equals(endpoint)
        ? Tags.LLMOBS_EMBEDDING_SPAN_KIND
        : Tags.LLMOBS_LLM_SPAN_KIND;
  }

  /** The response model resolves aliases the request used, so it wins. */
  private static String requestedModel(AgentSpan span) {
    String model = stringTag(span, CommonTags.OPENAI_RESPONSE_MODEL);
    return model != null ? model : stringTag(span, CommonTags.OPENAI_REQUEST_MODEL);
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
