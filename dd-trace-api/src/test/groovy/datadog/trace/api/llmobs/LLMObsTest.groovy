package datadog.trace.api.llmobs

import datadog.trace.api.llmobs.noop.NoOpLLMObsSpan
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory
import datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import java.lang.reflect.Field

class LLMObsTest extends DDSpecification {

  @Shared
  def originalSpanFactory
  @Shared
  def originalEvalProcessor

  def setupSpec() {
    // Store original values
    originalSpanFactory = getStaticField("SPAN_FACTORY")
    originalEvalProcessor = getStaticField("EVAL_PROCESSOR")
  }

  def cleanupSpec() {
    // Restore original values
    setStaticField("SPAN_FACTORY", originalSpanFactory)
    setStaticField("EVAL_PROCESSOR", originalEvalProcessor)
  }

  def cleanup() {
    // Reset to defaults after each test
    setStaticField("SPAN_FACTORY", NoOpLLMObsSpanFactory.INSTANCE)
    setStaticField("EVAL_PROCESSOR", NoOpLLMObsEvalProcessor.INSTANCE)
  }

  private static void setStaticField(String fieldName, Object value) {
    Field field = LLMObs.getDeclaredField(fieldName)
    field.setAccessible(true)
    field.set(null, value)
  }

  private static Object getStaticField(String fieldName) {
    Field field = LLMObs.getDeclaredField(fieldName)
    field.setAccessible(true)
    return field.get(null)
  }

  def "test ToolCall creation and getters"() {
    given:
    def arguments = [location: "New York", unit: "celsius"]

    when:
    def toolCall = LLMObs.ToolCall.from("get_weather", "function", "tool-123", arguments)

    then:
    toolCall.name == "get_weather"
    toolCall.type == "function"
    toolCall.toolId == "tool-123"
    toolCall.arguments == arguments
  }

  def "test ToolCall with null arguments"() {
    when:
    def toolCall = LLMObs.ToolCall.from("get_weather", "function", "tool-123", null)

    then:
    toolCall.name == "get_weather"
    toolCall.type == "function"
    toolCall.toolId == "tool-123"
    toolCall.arguments == null
  }

  def "test LLMMessage creation with toolCalls"() {
    given:
    def toolCall = LLMObs.ToolCall.from("get_weather", "function", "tool-123", [location: "Paris"])
    def toolCalls = [toolCall]

    when:
    def message = LLMObs.LLMMessage.from("assistant", "Let me check the weather", toolCalls)

    then:
    message.role == "assistant"
    message.content == "Let me check the weather"
    message.toolCalls == toolCalls
    message.toolCalls.size() == 1
    message.toolCalls[0].name == "get_weather"
    message.toolCalls[0].type == "function"
    message.toolCalls[0].toolId == "tool-123"
    message.toolCalls[0].arguments == [location: "Paris"]
  }

  def "test LLMMessage creation without toolCalls"() {
    when:
    def message = LLMObs.LLMMessage.from("user", "What's the weather like?")

    then:
    message.role == "user"
    message.content == "What's the weather like?"
    message.toolCalls == null
  }

  def "test LLMMessage with multiple toolCalls"() {
    given:
    def toolCall1 = LLMObs.ToolCall.from("get_weather", "function", "tool-1", [location: "New York"])
    def toolCall2 = LLMObs.ToolCall.from("get_stock_price", "function", "tool-2", [symbol: "AAPL"])
    def toolCalls = [toolCall1, toolCall2]

    when:
    def message = LLMObs.LLMMessage.from("assistant", "I'll help you with both requests", toolCalls)

    then:
    message.role == "assistant"
    message.content == "I'll help you with both requests"
    message.toolCalls == toolCalls
    message.toolCalls.size() == 2
    message.toolCalls[0].name == "get_weather"
    message.toolCalls[1].name == "get_stock_price"
  }

  def "test default NoOp span factory behavior"() {
    when:
    def llmSpan = LLMObs.startLLMSpan("test", "gpt-4", "openai", "app", "session")
    def agentSpan = LLMObs.startAgentSpan("test", "app", "session")
    def toolSpan = LLMObs.startToolSpan("test", "app", "session")
    def taskSpan = LLMObs.startTaskSpan("test", "app", "session")
    def workflowSpan = LLMObs.startWorkflowSpan("test", "app", "session")
    def embeddingSpan = LLMObs.startEmbeddingSpan("test", "app", "openai", "model", "session")
    def retrievalSpan = LLMObs.startRetrievalSpan("test", "app", "session")

    then:
    llmSpan == NoOpLLMObsSpan.INSTANCE
    agentSpan == NoOpLLMObsSpan.INSTANCE
    toolSpan == NoOpLLMObsSpan.INSTANCE
    taskSpan == NoOpLLMObsSpan.INSTANCE
    workflowSpan == NoOpLLMObsSpan.INSTANCE
    embeddingSpan == NoOpLLMObsSpan.INSTANCE
    retrievalSpan == NoOpLLMObsSpan.INSTANCE
  }

  def "test span creation with null optional parameters"() {
    when:
    def llmSpan = LLMObs.startLLMSpan("test", "gpt-4", "openai", null, null)
    def agentSpan = LLMObs.startAgentSpan("test", null, null)
    def toolSpan = LLMObs.startToolSpan("test", null, null)
    def taskSpan = LLMObs.startTaskSpan("test", null, null)
    def workflowSpan = LLMObs.startWorkflowSpan("test", null, null)
    def embeddingSpan = LLMObs.startEmbeddingSpan("test", null, null, null, null)
    def retrievalSpan = LLMObs.startRetrievalSpan("test", null, null)

    then:
    llmSpan == NoOpLLMObsSpan.INSTANCE
    agentSpan == NoOpLLMObsSpan.INSTANCE
    toolSpan == NoOpLLMObsSpan.INSTANCE
    taskSpan == NoOpLLMObsSpan.INSTANCE
    workflowSpan == NoOpLLMObsSpan.INSTANCE
    embeddingSpan == NoOpLLMObsSpan.INSTANCE
    retrievalSpan == NoOpLLMObsSpan.INSTANCE
  }

  def "test default NoOp evaluation processor behavior"() {
    when:
    // These should not throw exceptions
    LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", 0.5, [:])
    LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", 0.5, "app", [:])
    LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", "value", [:])
    LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", "value", "app", [:])

    then:
    noExceptionThrown()
  }

  def "test evaluation submission with various score values"() {
    given:
    def span = NoOpLLMObsSpan.INSTANCE
    def tags = [category: "test", version: "1.0"]

    when:
    LLMObs.SubmitEvaluation(span, "accuracy", 0.0, tags)
    LLMObs.SubmitEvaluation(span, "precision", 1.0, tags)
    LLMObs.SubmitEvaluation(span, "recall", 0.85, tags)
    LLMObs.SubmitEvaluation(span, "f1_score", 0.92, "myapp", tags)

    then:
    noExceptionThrown()
  }

  def "test evaluation submission with categorical values"() {
    given:
    def span = NoOpLLMObsSpan.INSTANCE
    def tags = [evaluator: "human", context: "production"]

    when:
    LLMObs.SubmitEvaluation(span, "quality", "excellent", tags)
    LLMObs.SubmitEvaluation(span, "relevance", "poor", tags)
    LLMObs.SubmitEvaluation(span, "toxicity", "safe", "content-app", tags)

    then:
    noExceptionThrown()
  }

  def "test evaluation submission with empty tags"() {
    given:
    def span = NoOpLLMObsSpan.INSTANCE
    def emptyTags = [:]

    when:
    LLMObs.SubmitEvaluation(span, "score", 0.75, emptyTags)
    LLMObs.SubmitEvaluation(span, "category", "good", emptyTags)

    then:
    noExceptionThrown()
  }

  def "test span creation with custom factory returns actual spans"() {
    given:
    def mockSpanFactory = Mock(LLMObs.LLMObsSpanFactory)
    def mockEvalProcessor = Mock(LLMObs.LLMObsEvalProcessor)

    def mockLLMSpan = Mock(LLMObsSpan)
    def mockAgentSpan = Mock(LLMObsSpan)
    def mockToolSpan = Mock(LLMObsSpan)
    def mockTaskSpan = Mock(LLMObsSpan)
    def mockWorkflowSpan = Mock(LLMObsSpan)
    def mockEmbeddingSpan = Mock(LLMObsSpan)
    def mockRetrievalSpan = Mock(LLMObsSpan)

    // Set up the custom factory
    setStaticField("SPAN_FACTORY", mockSpanFactory)
    setStaticField("EVAL_PROCESSOR", mockEvalProcessor)

    when:
    def llmSpan = LLMObs.startLLMSpan("chat-completion", "gpt-4", "openai", "my-app", "session-1")
    def agentSpan = LLMObs.startAgentSpan("agent-task", "my-app", "session-1")
    def toolSpan = LLMObs.startToolSpan("weather-tool", "my-app", "session-1")
    def taskSpan = LLMObs.startTaskSpan("summarize-task", "my-app", "session-1")
    def workflowSpan = LLMObs.startWorkflowSpan("data-workflow", "my-app", "session-1")
    def embeddingSpan = LLMObs.startEmbeddingSpan("text-embed", "my-app", "openai", "text-embedding-ada-002", "session-1")
    def retrievalSpan = LLMObs.startRetrievalSpan("document-retrieval", "my-app", "session-1")

    // Test evaluation submission
    LLMObs.SubmitEvaluation(mockLLMSpan, "accuracy", 0.95, [test: "value"])
    LLMObs.SubmitEvaluation(mockAgentSpan, "quality", "excellent", "eval-app", [reviewer: "human"])

    then:
    // Verify all span factory methods were called with correct parameters
    1 * mockSpanFactory.startLLMSpan("chat-completion", "gpt-4", "openai", "my-app", "session-1") >> mockLLMSpan
    1 * mockSpanFactory.startAgentSpan("agent-task", "my-app", "session-1") >> mockAgentSpan
    1 * mockSpanFactory.startToolSpan("weather-tool", "my-app", "session-1") >> mockToolSpan
    1 * mockSpanFactory.startTaskSpan("summarize-task", "my-app", "session-1") >> mockTaskSpan
    1 * mockSpanFactory.startWorkflowSpan("data-workflow", "my-app", "session-1") >> mockWorkflowSpan
    1 * mockSpanFactory.startEmbeddingSpan("text-embed", "my-app", "openai", "text-embedding-ada-002", "session-1") >> mockEmbeddingSpan
    1 * mockSpanFactory.startRetrievalSpan("document-retrieval", "my-app", "session-1") >> mockRetrievalSpan

    // Verify evaluation processor methods were called
    1 * mockEvalProcessor.SubmitEvaluation(mockLLMSpan, "accuracy", 0.95, [test: "value"])
    1 * mockEvalProcessor.SubmitEvaluation(mockAgentSpan, "quality", "excellent", "eval-app", [reviewer: "human"])

    // Verify the correct spans were returned
    llmSpan == mockLLMSpan
    agentSpan == mockAgentSpan
    toolSpan == mockToolSpan
    taskSpan == mockTaskSpan
    workflowSpan == mockWorkflowSpan
    embeddingSpan == mockEmbeddingSpan
    retrievalSpan == mockRetrievalSpan

    // Verify spans are not the NoOp instances
    llmSpan != NoOpLLMObsSpan.INSTANCE
    agentSpan != NoOpLLMObsSpan.INSTANCE
    toolSpan != NoOpLLMObsSpan.INSTANCE
    taskSpan != NoOpLLMObsSpan.INSTANCE
    workflowSpan != NoOpLLMObsSpan.INSTANCE
    embeddingSpan != NoOpLLMObsSpan.INSTANCE
    retrievalSpan != NoOpLLMObsSpan.INSTANCE
  }

  def "test span creation with null parameters using custom factory"() {
    given:
    def mockSpanFactory = Mock(LLMObs.LLMObsSpanFactory)
    def mockSpan = Mock(LLMObsSpan)

    setStaticField("SPAN_FACTORY", mockSpanFactory)

    when:
    def llmSpan = LLMObs.startLLMSpan("test-span", "gpt-4", "openai", null, null)
    def embeddingSpan = LLMObs.startEmbeddingSpan("embed-span", null, null, null, null)

    then:
    1 * mockSpanFactory.startLLMSpan("test-span", "gpt-4", "openai", null, null) >> mockSpan
    1 * mockSpanFactory.startEmbeddingSpan("embed-span", null, null, null, null) >> mockSpan

    llmSpan == mockSpan
    embeddingSpan == mockSpan
  }
}
