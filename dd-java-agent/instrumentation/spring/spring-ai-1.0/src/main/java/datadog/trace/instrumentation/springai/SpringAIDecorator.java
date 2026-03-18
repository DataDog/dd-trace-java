package datadog.trace.instrumentation.springai;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

public final class SpringAIDecorator extends ClientDecorator {
  public static final CharSequence SPRING_AI_REQUEST = UTF8BytesString.create("spring.ai.request");
  public static final CharSequence SPRING_AI = UTF8BytesString.create("spring-ai");
  public static final SpringAIDecorator DECORATE = new SpringAIDecorator();

  private static final String TAG_SPRING_AI_INPUT = "spring.ai.input";
  private static final String TAG_SPRING_AI_OUTPUT = "spring.ai.output";
  private static final String TAG_SPRING_AI_MODEL = "spring.ai.model";
  private static final String TAG_SPRING_AI_USAGE_INPUT_TOKENS = "spring.ai.usage.input_tokens";
  private static final String TAG_SPRING_AI_USAGE_OUTPUT_TOKENS = "spring.ai.usage.output_tokens";
  private static final String TAG_SPRING_AI_USAGE_TOTAL_TOKENS = "spring.ai.usage.total_tokens";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-ai"};
  }

  @Override
  protected CharSequence component() {
    return SPRING_AI;
  }

  @Override
  protected String service() {
    return null;
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  public void onPrompt(final AgentSpan span, final Object prompt) {
    span.setResourceName("ChatModel.call");
    final String input = SpringAIMessageExtractAdapter.extractPrompt(prompt);
    if (input != null) {
      span.setTag(TAG_SPRING_AI_INPUT, input);
    }
  }

  public void onResponse(final AgentSpan span, final Object response) {
    final String output = SpringAIMessageExtractAdapter.extractOutput(response);
    onOutput(span, output);
    final String model = SpringAIMessageExtractAdapter.extractModel(response);
    if (model != null) {
      span.setTag(TAG_SPRING_AI_MODEL, model);
    }
    onTokenUsage(span, response);
  }

  public void onOutput(final AgentSpan span, final String output) {
    if (output != null) {
      span.setTag(TAG_SPRING_AI_OUTPUT, output);
    }
  }

  public void onTokenUsage(final AgentSpan span, final Object response) {
    final Long inputTokens = SpringAIMessageExtractAdapter.extractInputTokens(response);
    if (inputTokens != null) {
      span.setTag(TAG_SPRING_AI_USAGE_INPUT_TOKENS, inputTokens);
    }

    final Long outputTokens = SpringAIMessageExtractAdapter.extractOutputTokens(response);
    if (outputTokens != null) {
      span.setTag(TAG_SPRING_AI_USAGE_OUTPUT_TOKENS, outputTokens);
    }

    final Long totalTokens = SpringAIMessageExtractAdapter.extractTotalTokens(response);
    if (totalTokens != null) {
      span.setTag(TAG_SPRING_AI_USAGE_TOTAL_TOKENS, totalTokens);
    }
  }
}
