package datadog.trace.instrumentation.openai_java;

import com.openai.core.ClientOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponse;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.List;

public class OpenAiDecorator extends ClientDecorator {
  public static final OpenAiDecorator DECORATE = new OpenAiDecorator();

  public static final String INSTRUMENTATION_NAME = "openai-java";
  public static final CharSequence SPAN_NAME = UTF8BytesString.create("openai.request");

  public static final String REQUEST_MODEL = "openai.request.model";
  public static final String RESPONSE_MODEL = "openai.response.model";
  public static final String OPENAI_ORGANIZATION_NAME = "openai.organization";

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("openai");

  public AgentSpan startSpan(ClientOptions clientOptions) {
    AgentSpan span = AgentTracer.startSpan(INSTRUMENTATION_NAME, SPAN_NAME);
    afterStart(span);
    withClientOptions(span, clientOptions);
    return span;
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
    span.setTag("_ml_obs_tag.parent_id", currentLlmParentSpanId());
    return super.afterStart(span);
  }

  private String currentLlmParentSpanId() {
    AgentSpanContext parentLlmContext = LLMObsContext.current();
    if (parentLlmContext == null) {
      return LLMObsContext.ROOT_SPAN_ID;
    }
    long parentLlmSpanId = parentLlmContext.getSpanId();
    if (parentLlmSpanId == DDSpanId.ZERO) {
      return LLMObsContext.ROOT_SPAN_ID;
    }
    return Long.toString(parentLlmSpanId);
  }

  public void withHttpResponse(AgentSpan span, HttpResponse response) {
    Headers headers = response.headers();
    setTagFromHeader(span, OPENAI_ORGANIZATION_NAME, headers, "openai-organization");

    setMetricFromHeader(
        span,
        "openai.organization.ratelimit.requests.limit",
        headers,
        "x-ratelimit-limit-requests");
    setMetricFromHeader(
        span,
        "openai.organization.ratelimit.requests.remaining",
        headers,
        "x-ratelimit-remaining-requests");
    setMetricFromHeader(
        span, "openai.organization.ratelimit.tokens.limit", headers, "x-ratelimit-limit-tokens");
    setMetricFromHeader(
        span,
        "openai.organization.ratelimit.tokens.remaining",
        headers,
        "x-ratelimit-remaining-tokens");
  }

  private static void setTagFromHeader(AgentSpan span, String tag, Headers headers, String header) {
    List<String> values = headers.values(header);
    if (values.isEmpty()) {
      return;
    }
    span.setTag(tag, values.get(0));
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
    } catch (NumberFormatException ex) {
      // ~
    }
  }

  public void withClientOptions(AgentSpan span, ClientOptions clientOptions) {
    span.setTag("openai.api_base", clientOptions.baseUrl());

    // TODO api_version (either last part of the URL, or api-version param if Azure)
    // clientOptions.queryParams().values("api-version")
  }
}
