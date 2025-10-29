package datadog.trace.instrumentation.openai_java;

import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionChoice;
import com.openai.models.completions.CompletionCreateParams;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

public class OpenAiDecorator extends ClientDecorator {
  public static final OpenAiDecorator DECORATE = new OpenAiDecorator();

  public static final String INSTRUMENTATION_NAME = "openai-java";
  public static final CharSequence SPAN_NAME = UTF8BytesString.create("openai.request");

  private static final CharSequence COMPLETIONS_CREATE = UTF8BytesString.create("completions.create");

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
    return null;
  }

  public void decorate(AgentSpan span, CompletionCreateParams params) {
    span.setResourceName(COMPLETIONS_CREATE);

    if (params == null) {
      return;
    }

    span.setTag("openai.request.model", params.model().toString());
    //TODO set other tags: ai.provider, openai.request.endpoint, request.prompt?
    //TODO max_tokens, temperature or these are LLMObs – non-APM?
  }

  public void decorate(AgentSpan span, Completion completion) {
    for (CompletionChoice choice : completion.choices()) {
      span.setTag("openai.response.choices." + choice.index() + ".text", choice.text()); // TODO
    }
  }
}
