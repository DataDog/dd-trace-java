import datadog.trace.api.config.LlmObsConfig

/**
 * With LLM Observability off the instrumentation still traces, so the gen_ai.* attributes it can
 * resolve without the LLMObs pipeline are emitted. Forked because OpenAiDecorator reads the
 * llmobs.enabled flag once, into a final field, when the class is loaded.
 */
class LlmObsDisabledForkedTest extends OpenAiTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(LlmObsConfig.LLMOBS_ENABLED, "false")
  }

  def "chat completion emits the gen_ai attributes available without LLMObs"() {
    when:
    openAiClient.chat().completions().create(chatCompletionCreateParams(false))
    TEST_WRITER.waitForTraces(1)
    def span = TEST_WRITER.flatten().find { it.operationName.toString() == "openai.request" }

    then:
    span.getTag("gen_ai.operation.name") == "llm"
    span.getTag("gen_ai.request.model") == span.getTag("openai.response.model")
    span.getTag("gen_ai.provider.name") == "openai"
    span.getTag("gen_ai.application.name") != null

    and: "neither is computed with LLMObs off"
    span.getTag("gen_ai.conversation.id") == null
    span.getTag("gen_ai.usage.input_tokens") == null
    span.getTag("gen_ai.usage.output_tokens") == null
    span.getTag("gen_ai.usage.total_tokens") == null

    and: "the LLMObs track stays off the span"
    span.getTag("_ml_obs_tag.span.kind") == null
  }

  def "embedding maps to the embedding operation"() {
    when:
    openAiClient.embeddings().create(embeddingCreateParams(false))
    TEST_WRITER.waitForTraces(1)
    def span = TEST_WRITER.flatten().find { it.operationName.toString() == "openai.request" }

    then:
    span.getTag("gen_ai.operation.name") == "embedding"
    span.getTag("gen_ai.request.model") == span.getTag("openai.response.model")
    span.getTag("gen_ai.provider.name") == "openai"
  }
}
