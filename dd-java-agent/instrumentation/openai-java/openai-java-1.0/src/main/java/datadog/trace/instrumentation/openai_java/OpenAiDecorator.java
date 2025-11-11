package datadog.trace.instrumentation.openai_java;

import com.openai.core.ClientOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponse;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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

  private static final CharSequence COMPLETIONS_CREATE = UTF8BytesString.create("completions.create");
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("openai");

  @Override
  protected String service() {
    return null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {
      INSTRUMENTATION_NAME
    };
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.LLMOBS;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  public void decorateCompletion(AgentSpan span, CompletionCreateParams params) {
    span.setResourceName(COMPLETIONS_CREATE);
    span.setTag("openai.request.endpoint", "v1/completions");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    span.setTag(REQUEST_MODEL, params.model().asString());

    //TODO set LLMObs tags (not visible to APM)
  }

  public void decorateWithCompletion(AgentSpan span, Completion completion) {
    span.setTag(RESPONSE_MODEL, completion.model());

    //TODO set LLMObs tags (not visible to APM)
  }

  public void decorateWithCompletions(AgentSpan span, List<Completion> completions) {
    if (!completions.isEmpty()) {
      span.setTag(RESPONSE_MODEL, completions.get(0).model());
    }

    //TODO set LLMObs tags (not visible to APM)
  }

  public void decorateWithResponse(AgentSpan span, HttpResponse response) {
    Headers headers = response.headers();
    setTagFromHeader(span, OPENAI_ORGANIZATION_NAME, headers, "openai-organization");

    setMetricFromHeader(span, "openai.organization.ratelimit.requests.limit", headers, "x-ratelimit-limit-requests");
    setMetricFromHeader(span, "openai.organization.ratelimit.requests.remaining", headers, "x-ratelimit-remaining-requests");
    setMetricFromHeader(span, "openai.organization.ratelimit.tokens.limit", headers, "x-ratelimit-limit-tokens");
    setMetricFromHeader(span, "openai.organization.ratelimit.tokens.remaining", headers, "x-ratelimit-remaining-tokens");
  }

  private static void setTagFromHeader(AgentSpan span, String tag, Headers headers, String header) {
    List<String> values = headers.values(header);
    if (values.isEmpty()) {
      return;
    }
    span.setTag(tag, values.get(0));
  }

  private static void setMetricFromHeader(AgentSpan span, String metric, Headers headers, String header) {
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

  public void decorateWithClientOptions(AgentSpan span, ClientOptions clientOptions) {
    span.setTag("openai.api_base", clientOptions.baseUrl());

    // TODO api_version (either last part of the URL, or api-version param if Azure)
    // clientOptions.queryParams().values("api-version")
  }
}
