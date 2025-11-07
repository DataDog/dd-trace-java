package datadog.trace.instrumentation.openai_java;

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

// TODO APM tags
// ("openai.request.endpoint", "/%s/%s" % (API_VERSION, endpoint))
//  API_VERSION = "v1"
//  ENDPOINT_NAME = "completions"
//  ENDPOINT_NAME = "chat/completions"
//  ENDPOINT_NAME = "embeddings"
//  ENDPOINT_NAME = "responses"
//
// ("openai.request.method", self.HTTP_METHOD_TYPE)
//
// _set_tag_str("openai.%s" % arg, str(args[idx]))
//  ("api_base", "api_type", "api_version")
//
// _set_tag_str("openai.response.%s" % resp_attr, str(getattr(resp, resp_attr, "")))
//  _response_attrs = ("model",)

  public void decorate(AgentSpan span, CompletionCreateParams params) {
    span.setResourceName(COMPLETIONS_CREATE);
    if (params == null) {
      return;
    }
    span.setTag(REQUEST_MODEL, params.model().toString());

    //TODO set LLMObs tags (not visible to APM)
  }

  public void decorate(AgentSpan span, Completion completion) {
    //TODO set LLMObs tags (not visible to APM)
  }

  public void decorate(AgentSpan span, List<Completion> completions) {
    //TODO set LLMObs tags (not visible to APM)
  }
}
