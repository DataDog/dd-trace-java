package datadog.trace.core.jfr.openjdk

import datadog.trace.api.DDId
import datadog.trace.api.config.ProfilingConfig
import datadog.trace.api.sampling.AdaptiveSampler
import datadog.trace.bootstrap.config.provider.ConfigProvider
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

class JfrCheckpointerTest extends DDSpecification {
  def "test span checkpoint sample"() {
    setup:
    AdaptiveSampler sampler = stubbedSampler(sampled)

    JFRCheckpointer checkpointer = Spy(new JFRCheckpointer(sampler, ConfigProvider.getInstance()))
    checkpointer.emitCheckpoint(_, _) >> {}
    checkpointer.dropCheckpoint() >> {}

    AgentSpan span = mockSpan(checkpointed)

    when:
    checkpointer.checkpoint(span, 0)
    then:
    setEmitting * span.setEmittingCheckpoints(true)
    setDropping * span.setEmittingCheckpoints(false)
    checkpoints * checkpointer.emitCheckpoint(span, 0)
    (1 - checkpoints) * checkpointer.dropCheckpoint()

    where:
    checkpointed | sampled | checkpoints | setEmitting | setDropping
    null         | true    | 1           | 1           | 0
    null         | false   | 0           | 0           | 1
    true         | true    | 1           | 0           | 0
    true         | false   | 1           | 0           | 0
    false        | true    | 0           | 0           | 0
    false        | false   | 0           | 0           | 0
  }

  def "test sampler configuration"() {
    setup:
    Properties props = new Properties()
    props.put(ProfilingConfig.PROFILING_CHECKPOINTS_RATE_LIMIT, String.valueOf(rateLimit))
    props.put(ProfilingConfig.PROFILING_CHECKPOINTS_RATE_SENSITIVITY_MS, String.valueOf(sensitivity))
    def configProvider = ConfigProvider.withPropertiesOverride(props)
    when:
    def config = JFRCheckpointer.getSamplerConfiguration(configProvider)

    then:
    config.windowSize.toMillis() == windowSize
    config.samplesPerWindow == samplesPerWindow

    where:
    rateLimit   | sensitivity | windowSize                                  | samplesPerWindow
    // linearly scaled parameters
    100000L     | 10000L      | 10000L                                      | Math.round(rateLimit * windowSize / 60000f)
    100000L     | 1L          | JFRCheckpointer.MIN_SAMPLER_WINDOW_SIZE_MS  | Math.round(rateLimit * windowSize / 60000f)
    100000L     | 60000L      | JFRCheckpointer.MAX_SAMPLER_WINDOW_SIZE_MS  | Math.round(rateLimit * windowSize / 60000f)
    // too hight rate limit value is clipped
    100000000L  | 10000L      | 10000L                                      | Math.round(JFRCheckpointer.MAX_SAMPLER_RATE * windowSize / 60000f)
    // unreasonably low rate limit value is extended
    1L          | 10000L      | JFRCheckpointer.MAX_SAMPLER_WINDOW_SIZE_MS  | 1
  }

  def stubbedSampler(def sampled) {
    AdaptiveSampler sampler = Stub(AdaptiveSampler)
    sampler.drop() >> false
    sampler.keep() >> true
    sampler.sample() >> sampled
    return sampler
  }

  private static localRootSpanId = 1L
  private static spanId = 1L

  def mockSpan(def checkpointed, def dropping = false, def resource = "foo") {
    DDId localRootSpanId = DDId.from(localRootSpanId++)
    DDId spanId = DDId.from(spanId++)

    def localRootSpan = Mock(AgentSpan)
    localRootSpan.getSpanId() >> localRootSpanId
    localRootSpan.getResourceName() >> UTF8BytesString.create(resource)

    def span = Mock(AgentSpan)
    span.eligibleForDropping() >> dropping
    span.getSpanId() >> spanId
    span.getResourceName() >> UTF8BytesString.create(resource)
    span.getLocalRootSpan() >> localRootSpan
    span.isEmittingCheckpoints() >> checkpointed

    return span
  }
}
