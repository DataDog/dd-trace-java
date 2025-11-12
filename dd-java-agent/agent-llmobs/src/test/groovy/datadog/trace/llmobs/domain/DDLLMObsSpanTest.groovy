package datadog.trace.llmobs.domain

import datadog.trace.agent.tooling.TracerInstaller
import datadog.trace.api.DDTags
import datadog.trace.api.IdGenerationStrategy
import datadog.trace.api.WellKnownTags
import datadog.trace.api.llmobs.LLMObs
import datadog.trace.api.llmobs.LLMObsSpan
import datadog.trace.api.llmobs.LLMObsTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import org.apache.groovy.util.Maps
import spock.lang.Shared

class DDLLMObsSpanTest  extends DDSpecification{
  @SuppressWarnings('PropertyName')
  @Shared
  AgentTracer.TracerAPI TEST_TRACER

  void setupSpec() {
    TEST_TRACER =
      Spy(
      CoreTracer.builder()
      .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
      .build())
    TracerInstaller.forceInstallGlobalTracer(TEST_TRACER)

    TEST_TRACER.startSpan(*_) >> {
      def agentSpan = callRealMethod()
      agentSpan
    }
  }

  void cleanupSpec() {
    TEST_TRACER?.close()
  }

  void setup() {
    assert TEST_TRACER.activeSpan() == null: "Span is active before test has started: " + TEST_TRACER.activeSpan()
    TEST_TRACER.flush()
  }

  void cleanup() {
    TEST_TRACER.flush()
  }

  // Prefix for tags
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag."
  // Prefix for metrics
  private static final String LLMOBS_METRIC_PREFIX = "_ml_obs_metric."

  // internal tags to be prefixed
  private static final String INPUT = LLMOBS_TAG_PREFIX + "input"
  private static final String OUTPUT = LLMOBS_TAG_PREFIX + "output"
  private static final String METADATA = LLMOBS_TAG_PREFIX + LLMObsTags.METADATA


  def "test span simple"() {
    setup:
    def test = givenALLMObsSpan(Tags.LLMOBS_WORKFLOW_SPAN_KIND, "test-span")

    when:
    def input = "test input"
    def output = "test output"
    // initial set
    test.annotateIO(input, output)
    test.setMetadata(Maps.of("sport", "baseball", "price_data", Maps.of("gpt4", 100)))
    test.setMetrics(Maps.of("rank", 1))
    test.setMetric("likelihood", 0.1)
    test.setTag("DOMAIN", "north-america")
    test.setTags(Maps.of("bulk1", 1, "bulk2", "2"))
    def errMsg = "mr brady"
    test.setErrorMessage(errMsg)

    then:
    def innerSpan = (AgentSpan)test.span
    Tags.LLMOBS_WORKFLOW_SPAN_KIND.equals(innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"))

    null == innerSpan.getTag("input")
    input.equals(innerSpan.getTag(INPUT))
    null == innerSpan.getTag("output")
    output.equals(innerSpan.getTag(OUTPUT))

    null == innerSpan.getTag("metadata")
    def expectedMetadata = Maps.of("sport", "baseball", "price_data", Maps.of("gpt4", 100))
    expectedMetadata.equals(innerSpan.getTag(METADATA))

    null == innerSpan.getTag("rank")
    def rankMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "rank")
    rankMetric instanceof Number && 1 == (int)rankMetric

    null == innerSpan.getTag("likelihood")
    def likelihoodMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "likelihood")
    likelihoodMetric instanceof Number
    0.1 == (double)likelihoodMetric

    null == innerSpan.getTag("DOMAIN")
    def domain = innerSpan.getTag(LLMOBS_TAG_PREFIX + "DOMAIN")
    domain instanceof String
    "north-america".equals((String)domain)

    null == innerSpan.getTag("bulk1")
    def tagBulk1 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk1")
    tagBulk1 instanceof Number
    1 == ((int)tagBulk1)

    null == innerSpan.getTag("bulk2")
    def tagBulk2 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk2")
    tagBulk2 instanceof String
    "2".equals((String)tagBulk2)

    innerSpan.isError()
    innerSpan.getTag(DDTags.ERROR_MSG) instanceof String
    errMsg.equals(innerSpan.getTag(DDTags.ERROR_MSG))

    null == innerSpan.getTag("env")
    def tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env")
    tagEnv instanceof UTF8BytesString
    "test-env" == tagEnv.toString()

    null == innerSpan.getTag("service")
    def tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service")
    tagSvc instanceof UTF8BytesString
    "test-svc" == tagSvc.toString()

    null == innerSpan.getTag("version")
    def tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version")
    tagVersion instanceof UTF8BytesString
    "v1" == tagVersion.toString()
  }

  def "test span with overwrites"() {
    setup:
    def test = givenALLMObsSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "test-span")

    when:
    def input = "test input"
    // initial set
    test.annotateIO(input, "test output")
    // this should be a no-op
    test.annotateIO("", "")
    // this should replace the initial output
    def expectedOutput = Arrays.asList(Maps.of("role", "user", "content", "how much is gas"))
    test.annotateIO(null, expectedOutput)

    // initial set
    test.setMetadata(Maps.of("sport", "baseball", "price_data", Maps.of("gpt4", 100)))
    // this should replace baseball with hockey
    test.setMetadata(Maps.of("sport", "hockey"))
    // this should add a new key
    test.setMetadata(Maps.of("temperature", 30))

    // initial set
    test.setMetrics(Maps.of("rank", 1))
    // this should replace the metric
    test.setMetric("rank", 10)

    // initial set
    test.setTag("DOMAIN", "north-america")
    // add and replace
    test.setTags(Maps.of("bulk1", 1, "DOMAIN", "europe"))

    def throwableMsg = "false positive"
    test.addThrowable(new Throwable(throwableMsg))
    test.setError(false)

    then:
    def innerSpan = (AgentSpan)test.span
    Tags.LLMOBS_AGENT_SPAN_KIND.equals(innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"))

    null == innerSpan.getTag("input")
    input.equals(innerSpan.getTag(INPUT))
    null == innerSpan.getTag("output")
    expectedOutput.equals(innerSpan.getTag(OUTPUT))

    null == innerSpan.getTag("metadata")
    def expectedMetadata = Maps.of("sport", "hockey", "price_data", Maps.of("gpt4", 100), "temperature", 30)
    expectedMetadata.equals(innerSpan.getTag(METADATA))

    null == innerSpan.getTag("rank")
    def rankMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "rank")
    rankMetric instanceof Number && 10 == (int)rankMetric

    null == innerSpan.getTag("DOMAIN")
    def domain = innerSpan.getTag(LLMOBS_TAG_PREFIX + "DOMAIN")
    domain instanceof String
    "europe".equals((String)domain)

    null == innerSpan.getTag("bulk1")
    def tagBulk1 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk1")
    tagBulk1 instanceof Number
    1 == ((int)tagBulk1)

    !innerSpan.isError()
    innerSpan.getTag(DDTags.ERROR_MSG) instanceof String
    throwableMsg.equals(innerSpan.getTag(DDTags.ERROR_MSG))
    innerSpan.getTag(DDTags.ERROR_STACK) instanceof String
    ((String)innerSpan.getTag(DDTags.ERROR_STACK)).contains(throwableMsg)


    null == innerSpan.getTag("env")
    def tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env")
    tagEnv instanceof UTF8BytesString
    "test-env" == tagEnv.toString()

    null == innerSpan.getTag("service")
    def tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service")
    tagSvc instanceof UTF8BytesString
    "test-svc" == tagSvc.toString()

    null == innerSpan.getTag("version")
    def tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version")
    tagVersion instanceof UTF8BytesString
    "v1" == tagVersion.toString()
  }

  def "test llm span string input formatted to messages"() {
    setup:
    def test = givenALLMObsSpan(Tags.LLMOBS_LLM_SPAN_KIND, "test-span")

    when:
    def input = "test input"
    def output = "test output"
    // initial set
    test.annotateIO(input, output)

    then:
    def innerSpan = (AgentSpan)test.span
    Tags.LLMOBS_LLM_SPAN_KIND.equals(innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"))

    null == innerSpan.getTag("input")
    def spanInput = innerSpan.getTag(INPUT)
    spanInput instanceof List
    ((List)spanInput).size() == 1
    spanInput.get(0) instanceof LLMObs.LLMMessage
    def expectedInputMsg = LLMObs.LLMMessage.from("unknown", input)
    expectedInputMsg.getContent().equals(input)
    expectedInputMsg.getRole().equals("unknown")
    expectedInputMsg.getToolCalls().equals(null)

    null == innerSpan.getTag("output")
    def spanOutput = innerSpan.getTag(OUTPUT)
    spanOutput instanceof List
    ((List)spanOutput).size() == 1
    spanOutput.get(0) instanceof LLMObs.LLMMessage
    def expectedOutputMsg = LLMObs.LLMMessage.from("unknown", output)
    expectedOutputMsg.getContent().equals(output)
    expectedOutputMsg.getRole().equals("unknown")
    expectedOutputMsg.getToolCalls().equals(null)


    null == innerSpan.getTag("env")
    def tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env")
    tagEnv instanceof UTF8BytesString
    "test-env" == tagEnv.toString()

    null == innerSpan.getTag("service")
    def tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service")
    tagSvc instanceof UTF8BytesString
    "test-svc" == tagSvc.toString()

    null == innerSpan.getTag("version")
    def tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version")
    tagVersion instanceof UTF8BytesString
    "v1" == tagVersion.toString()
  }

  def "test llm span with messages"() {
    setup:
    def test = givenALLMObsSpan(Tags.LLMOBS_LLM_SPAN_KIND, "test-span")

    when:
    def inputMsg =  LLMObs.LLMMessage.from("user", "input")
    def outputMsg = LLMObs.LLMMessage.from("assistant", "output", Arrays.asList(LLMObs.ToolCall.from("weather-tool", "function", "6176241000", Maps.of("location", "paris"))))
    // initial set
    test.annotateIO(Arrays.asList(inputMsg), Arrays.asList(outputMsg))

    then:
    def innerSpan = (AgentSpan)test.span
    Tags.LLMOBS_LLM_SPAN_KIND.equals(innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"))

    null == innerSpan.getTag("input")
    def spanInput = innerSpan.getTag(INPUT)
    spanInput instanceof List
    ((List)spanInput).size() == 1
    def spanInputMsg = spanInput.get(0)
    spanInputMsg instanceof LLMObs.LLMMessage
    spanInputMsg.getContent().equals(inputMsg.getContent())
    spanInputMsg.getRole().equals("user")
    spanInputMsg.getToolCalls().equals(null)

    null == innerSpan.getTag("output")
    def spanOutput = innerSpan.getTag(OUTPUT)
    spanOutput instanceof List
    ((List)spanOutput).size() == 1
    def spanOutputMsg = spanOutput.get(0)
    spanOutputMsg instanceof LLMObs.LLMMessage
    spanOutputMsg.getContent().equals(outputMsg.getContent())
    spanOutputMsg.getRole().equals("assistant")
    spanOutputMsg.getToolCalls().size() == 1
    def toolCall = spanOutputMsg.getToolCalls().get(0)
    toolCall.getName().equals("weather-tool")
    toolCall.getType().equals("function")
    toolCall.getToolId().equals("6176241000")
    def expectedToolArgs = Maps.of("location", "paris")
    toolCall.getArguments().equals(expectedToolArgs)

    null == innerSpan.getTag("env")
    def tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env")
    tagEnv instanceof UTF8BytesString
    "test-env" == tagEnv.toString()

    null == innerSpan.getTag("service")
    def tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service")
    tagSvc instanceof UTF8BytesString
    "test-svc" == tagSvc.toString()

    null == innerSpan.getTag("version")
    def tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version")
    tagVersion instanceof UTF8BytesString
    "v1" == tagVersion.toString()
  }

  private LLMObsSpan givenALLMObsSpan(String kind, name){
    new DDLLMObsSpan(kind, name, "test-ml-app", null, "test-svc", new WellKnownTags("test-runtime-1", "host-1", "test-env", "test-svc", "v1", "java"))
  }
}
