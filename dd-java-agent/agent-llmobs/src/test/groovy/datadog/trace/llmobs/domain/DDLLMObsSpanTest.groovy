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
    assert Tags.LLMOBS_WORKFLOW_SPAN_KIND.equals(innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"))

    assert null == innerSpan.getTag("input")
    assert input.equals(innerSpan.getTag(INPUT))
    assert null == innerSpan.getTag("output")
    assert output.equals(innerSpan.getTag(OUTPUT))

    assert null == innerSpan.getTag("metadata")
    def expectedMetadata = Maps.of("sport", "baseball", "price_data", Maps.of("gpt4", 100))
    assert expectedMetadata.equals(innerSpan.getTag(METADATA))

    assert null == innerSpan.getTag("rank")
    def rankMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "rank")
    assert rankMetric instanceof Number && 1 == (int)rankMetric

    assert null == innerSpan.getTag("likelihood")
    def likelihoodMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "likelihood")
    assert likelihoodMetric instanceof Number
    assert 0.1 == (double)likelihoodMetric

    assert null == innerSpan.getTag("DOMAIN")
    def domain = innerSpan.getTag(LLMOBS_TAG_PREFIX + "DOMAIN")
    assert domain instanceof String
    assert "north-america".equals((String)domain)

    assert null == innerSpan.getTag("bulk1")
    def tagBulk1 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk1")
    assert tagBulk1 instanceof Number
    assert 1 == ((int)tagBulk1)

    assert null == innerSpan.getTag("bulk2")
    def tagBulk2 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk2")
    assert tagBulk2 instanceof String
    assert "2".equals((String)tagBulk2)

    assert innerSpan.isError()
    assert innerSpan.getTag(DDTags.ERROR_MSG) instanceof String
    assert errMsg.equals(innerSpan.getTag(DDTags.ERROR_MSG))

    assert null == innerSpan.getTag("env")
    def tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env")
    assert tagEnv instanceof UTF8BytesString
    assert "test-env" == tagEnv.toString()

    assert null == innerSpan.getTag("service")
    def tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service")
    assert tagSvc instanceof UTF8BytesString
    assert "test-svc" == tagSvc.toString()

    assert null == innerSpan.getTag("version")
    def tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version")
    assert tagVersion instanceof UTF8BytesString
    assert "v1" == tagVersion.toString()
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
    assert Tags.LLMOBS_AGENT_SPAN_KIND.equals(innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"))

    assert null == innerSpan.getTag("input")
    assert input.equals(innerSpan.getTag(INPUT))
    assert null == innerSpan.getTag("output")
    assert expectedOutput.equals(innerSpan.getTag(OUTPUT))

    assert null == innerSpan.getTag("metadata")
    def expectedMetadata = Maps.of("sport", "hockey", "price_data", Maps.of("gpt4", 100), "temperature", 30)
    assert expectedMetadata.equals(innerSpan.getTag(METADATA))

    assert null == innerSpan.getTag("rank")
    def rankMetric = innerSpan.getTag(LLMOBS_METRIC_PREFIX + "rank")
    assert rankMetric instanceof Number && 10 == (int)rankMetric

    assert null == innerSpan.getTag("DOMAIN")
    def domain = innerSpan.getTag(LLMOBS_TAG_PREFIX + "DOMAIN")
    assert domain instanceof String
    assert "europe".equals((String)domain)

    assert null == innerSpan.getTag("bulk1")
    def tagBulk1 = innerSpan.getTag(LLMOBS_TAG_PREFIX + "bulk1")
    assert tagBulk1 instanceof Number
    assert 1 == ((int)tagBulk1)

    assert !innerSpan.isError()
    assert innerSpan.getTag(DDTags.ERROR_MSG) instanceof String
    assert throwableMsg.equals(innerSpan.getTag(DDTags.ERROR_MSG))
    assert innerSpan.getTag(DDTags.ERROR_STACK) instanceof String
    assert ((String)innerSpan.getTag(DDTags.ERROR_STACK)).contains(throwableMsg)


    assert null == innerSpan.getTag("env")
    def tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env")
    assert tagEnv instanceof UTF8BytesString
    assert "test-env" == tagEnv.toString()

    assert null == innerSpan.getTag("service")
    def tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service")
    assert tagSvc instanceof UTF8BytesString
    assert "test-svc" == tagSvc.toString()

    assert null == innerSpan.getTag("version")
    def tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version")
    assert tagVersion instanceof UTF8BytesString
    assert "v1" == tagVersion.toString()
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
    assert Tags.LLMOBS_LLM_SPAN_KIND.equals(innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"))

    assert null == innerSpan.getTag("input")
    def spanInput = innerSpan.getTag(INPUT)
    assert spanInput instanceof List
    assert ((List)spanInput).size() == 1
    assert spanInput.get(0) instanceof LLMObs.LLMMessage
    def expectedInputMsg = LLMObs.LLMMessage.from("unknown", input)
    assert expectedInputMsg.getContent().equals(input)
    assert expectedInputMsg.getRole().equals("unknown")
    assert expectedInputMsg.getToolCalls().equals(null)

    assert null == innerSpan.getTag("output")
    def spanOutput = innerSpan.getTag(OUTPUT)
    assert spanOutput instanceof List
    assert ((List)spanOutput).size() == 1
    assert spanOutput.get(0) instanceof LLMObs.LLMMessage
    def expectedOutputMsg = LLMObs.LLMMessage.from("unknown", output)
    assert expectedOutputMsg.getContent().equals(output)
    assert expectedOutputMsg.getRole().equals("unknown")
    assert expectedOutputMsg.getToolCalls().equals(null)


    assert null == innerSpan.getTag("env")
    def tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env")
    assert tagEnv instanceof UTF8BytesString
    assert "test-env" == tagEnv.toString()

    assert null == innerSpan.getTag("service")
    def tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service")
    assert tagSvc instanceof UTF8BytesString
    assert "test-svc" == tagSvc.toString()

    assert null == innerSpan.getTag("version")
    def tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version")
    assert tagVersion instanceof UTF8BytesString
    assert "v1" == tagVersion.toString()
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
    assert Tags.LLMOBS_LLM_SPAN_KIND.equals(innerSpan.getTag(LLMOBS_TAG_PREFIX + "span.kind"))

    assert null == innerSpan.getTag("input")
    def spanInput = innerSpan.getTag(INPUT)
    assert spanInput instanceof List
    assert ((List)spanInput).size() == 1
    def spanInputMsg = spanInput.get(0)
    assert spanInputMsg instanceof LLMObs.LLMMessage
    assert spanInputMsg.getContent().equals(inputMsg.getContent())
    assert spanInputMsg.getRole().equals("user")
    assert spanInputMsg.getToolCalls().equals(null)

    assert null == innerSpan.getTag("output")
    def spanOutput = innerSpan.getTag(OUTPUT)
    assert spanOutput instanceof List
    assert ((List)spanOutput).size() == 1
    def spanOutputMsg = spanOutput.get(0)
    assert spanOutputMsg instanceof LLMObs.LLMMessage
    assert spanOutputMsg.getContent().equals(outputMsg.getContent())
    assert spanOutputMsg.getRole().equals("assistant")
    assert spanOutputMsg.getToolCalls().size() == 1
    def toolCall = spanOutputMsg.getToolCalls().get(0)
    assert toolCall.getName().equals("weather-tool")
    assert toolCall.getType().equals("function")
    assert toolCall.getToolId().equals("6176241000")
    def expectedToolArgs = Maps.of("location", "paris")
    assert toolCall.getArguments().equals(expectedToolArgs)

    assert null == innerSpan.getTag("env")
    def tagEnv = innerSpan.getTag(LLMOBS_TAG_PREFIX + "env")
    assert tagEnv instanceof UTF8BytesString
    assert "test-env" == tagEnv.toString()

    assert null == innerSpan.getTag("service")
    def tagSvc = innerSpan.getTag(LLMOBS_TAG_PREFIX + "service")
    assert tagSvc instanceof UTF8BytesString
    assert "test-svc" == tagSvc.toString()

    assert null == innerSpan.getTag("version")
    def tagVersion = innerSpan.getTag(LLMOBS_TAG_PREFIX + "version")
    assert tagVersion instanceof UTF8BytesString
    assert "v1" == tagVersion.toString()
  }

  private LLMObsSpan givenALLMObsSpan(String kind, name){
    new DDLLMObsSpan(kind, name, "test-ml-app", null, "test-svc", new WellKnownTags("test-runtime-1", "host-1", "test-env", "test-svc", "v1", "java"))
  }
}
