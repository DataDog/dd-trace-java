import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.context.ContextScope
import datadog.trace.api.llmobs.LLMObsContext
import datadog.trace.bootstrap.instrumentation.api.AgentTracer

/**
 * Verifies that auto-instrumented openai.request spans inherit session_id from an active
 * LLMObs parent context, matching the behavior of dd-trace-py and dd-trace-js.
 *
 * Background: customer apps typically set session_id on a manual LLMObs workflow root
 * via LLMObs.startWorkflowSpan(..., sessionId) and then call OpenAI without manually
 * wrapping in LLMObs.startLLMSpan. The auto-instrumented LLM span needs to inherit the
 * session_id from the workflow parent so the trace appears under its session in the
 * LLM Trace Explorer's Sessions view.
 *
 * Note: this test attaches the LLMObsContext directly rather than going through
 * LLMObs.startWorkflowSpan() — the latter resolves to a no-op factory in this
 * test module since agent-llmobs isn't loaded here. We're specifically validating
 * OpenAiDecorator's session_id inheritance behavior, which only depends on
 * LLMObsContext.currentSessionId() being set.
 */
class SessionIdPropagationTest extends OpenAiTest {

  def "openai.request span inherits session_id from active LLMObs context"() {
    setup:
    def expectedSessionId = "session-propagation-test-abc"

    when:
    runUnderTrace("parent") {
      def parentCtx = AgentTracer.activeSpan().context()
      ContextScope scope = LLMObsContext.attach(parentCtx, expectedSessionId)
      try {
        openAiClient.chat().completions().create(chatCompletionCreateParams(false))
      } finally {
        scope.close()
      }
    }
    TEST_WRITER.waitForTraces(1)
    def openAiSpan = TEST_WRITER.flatten().find {
      it.operationName.toString() == "openai.request"
    }

    then:
    openAiSpan != null
    expectedSessionId == openAiSpan.getTag("_ml_obs_tag.session_id")
  }

  def "openai.request span has no session_id when no LLMObs parent context is active"() {
    when:
    runUnderTrace("non-llmobs-parent") {
      openAiClient.chat().completions().create(chatCompletionCreateParams(false))
    }
    TEST_WRITER.waitForTraces(1)
    def openAiSpan = TEST_WRITER.flatten().find {
      it.operationName.toString() == "openai.request"
    }

    then:
    openAiSpan != null
    null == openAiSpan.getTag("_ml_obs_tag.session_id")
  }
}
