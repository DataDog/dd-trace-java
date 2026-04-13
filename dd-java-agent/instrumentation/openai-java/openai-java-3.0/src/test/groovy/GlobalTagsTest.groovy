import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

/**
 * Verifies that global tags (dd.trace.global.tags / DD_TAGS) are propagated to LLMObs span tags in the OpenAI instrumentation.
 */
class GlobalTagsTest extends OpenAiTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.global.tags", "team:backend,owner:ml-platform")
  }

  def "global dd_tags are included in OpenAI LLMObs span tags"() {
    when:
    runUnderTrace("parent") {
      openAiClient.chat().completions().create(chatCompletionCreateParams(false))
    }
    TEST_WRITER.waitForTraces(1)
    def openAiSpan = TEST_WRITER.flatten().find { it.operationName.toString() == "openai.request" }

    then:
    openAiSpan.getTag("_ml_obs_tag.team") == "backend"
    openAiSpan.getTag("_ml_obs_tag.owner") == "ml-platform"
  }
}
