package datadog.trace.core

import datadog.trace.api.Config
import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.common.sampling.AllSampler
import datadog.trace.common.sampling.PrioritySampler
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.sampling.Sampler
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.core.propagation.DatadogHttpCodec
import datadog.trace.core.propagation.HttpCodec
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Timeout

import static datadog.trace.api.config.GeneralConfig.ENV
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME
import static datadog.trace.api.config.GeneralConfig.VERSION
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE

@Timeout(10)
class CoreTracerTest extends DDCoreSpecification {

  def "verify defaults on tracer"() {
    when:
    def tracer = CoreTracer.builder().build()

    then:
    tracer.serviceName != ""
    tracer.sampler instanceof RateByServiceSampler
    tracer.writer instanceof DDAgentWriter
    tracer.statsDClient != null && tracer.statsDClient != StatsDClient.NO_OP

    tracer.injector instanceof HttpCodec.CompoundInjector
    tracer.extractor instanceof HttpCodec.CompoundExtractor

    cleanup:
    tracer.close()
  }

  def "verify disabling health monitor"() {
    setup:
    injectSysConfig(HEALTH_METRICS_ENABLED, "false")

    when:
    def tracer = CoreTracer.builder().build()

    then:
    tracer.statsDClient == StatsDClient.NO_OP

    cleanup:
    tracer.close()
  }

  def "verify service, env, and version are added as stats tags"() {
    setup:
    def expectedSize = 6
    if (service != null) {
      injectSysConfig(SERVICE_NAME, service)
    }

    if (env != null) {
      injectSysConfig(ENV, env)
      expectedSize += 1
    }

    if (version != null) {
      injectSysConfig(VERSION, version)
      expectedSize += 1
    }

    when:
    def constantTags = CoreTracer.generateConstantTags(new Config())

    then:
    constantTags.size() == expectedSize
    assert constantTags.any { it == CoreTracer.LANG_STATSD_TAG + ":java" }
    assert constantTags.any { it.startsWith(CoreTracer.LANG_VERSION_STATSD_TAG + ":") }
    assert constantTags.any { it.startsWith(CoreTracer.LANG_INTERPRETER_STATSD_TAG + ":") }
    assert constantTags.any { it.startsWith(CoreTracer.LANG_INTERPRETER_VENDOR_STATSD_TAG + ":") }
    assert constantTags.any { it.startsWith(CoreTracer.TRACER_VERSION_STATSD_TAG + ":") }

    if (service == null) {
      assert constantTags.any { it.startsWith("service:") }
    } else {
      assert constantTags.any { it == "service:" + service }
    }

    if (env != null) {
      assert constantTags.any { it == "env:" + env }
    }

    if (version != null) {
      assert constantTags.any { it == "version:" + version }
    }

    where:
    service       | env       | version
    null          | null      | null
    "testService" | null      | null
    "testService" | "staging" | null
    "testService" | null      | "1"
    "testService" | "staging" | "1"
    null          | "staging" | null
    null          | "staging" | "1"
    null          | null      | "1"
  }

  def "verify overriding sampler"() {
    setup:
    injectSysConfig(PRIORITY_SAMPLING, "false")

    when:
    def tracer = tracerBuilder().build()

    then:
    tracer.sampler instanceof AllSampler

    cleanup:
    tracer.close()
  }

  def "verify overriding writer"() {
    setup:
    injectSysConfig(WRITER_TYPE, "LoggingWriter")

    when:
    def tracer = tracerBuilder().build()

    then:
    tracer.writer instanceof LoggingWriter

    cleanup:
    tracer.close()
  }

  def "verify uds+windows"() {
    setup:
    System.setProperty("os.name", "Windows ME")

    when:
    injectSysConfig(AGENT_UNIX_DOMAIN_SOCKET, uds)

    then:
    Config.get().getAgentUnixDomainSocket() == uds

    where:
    uds = "asdf"
  }

  def "verify mapping configs on tracer"() {
    setup:
    injectSysConfig(SERVICE_MAPPING, mapString)
    injectSysConfig(SPAN_TAGS, mapString)
    injectSysConfig(HEADER_TAGS, mapString)

    when:
    def tracer = tracerBuilder().build()
    // Datadog extractor gets placed first
    def taggedHeaders = tracer.extractor.extractors[0].taggedHeaders

    then:
    tracer.defaultSpanTags == map
    tracer.serviceNameMappings == map
    taggedHeaders == map

    cleanup:
    tracer.close()

    where:
    mapString       | map
    "a:1, a:2, a:3" | [a: "3"]
    "a:b,c:d,e:"    | [a: "b", c: "d"]
  }

  def "verify overriding host"() {
    when:
    injectSysConfig(key, value)

    then:
    Config.get().getAgentHost() == value

    where:
    key          | value
    "agent.host" | "somethingelse"
  }

  def "verify overriding port"() {
    when:
    injectSysConfig(key, value)

    then:
    Config.get().getAgentPort() == Integer.valueOf(value)

    where:
    key                | value
    "agent.port"       | "777"
    "trace.agent.port" | "9999"
  }

  def "Writer is instance of LoggingWriter when property set"() {
    when:
    injectSysConfig("writer.type", "LoggingWriter")
    def tracer = tracerBuilder().build()

    then:
    tracer.writer instanceof LoggingWriter

    cleanup:
    tracer.close()
  }

  def "Shares TraceCount with DDApi with #key = #value"() {
    setup:
    injectSysConfig(key, value)
    final CoreTracer tracer = tracerBuilder().build()

    expect:
    tracer.writer instanceof DDAgentWriter

    cleanup:
    tracer.close()

    where:
    key               | value
    PRIORITY_SAMPLING | "true"
    PRIORITY_SAMPLING | "false"
  }

  def "root tags are applied only to root spans"() {
    setup:
    def tracer = tracerBuilder().localRootSpanTags(['only_root': 'value']).build()
    def root = tracer.buildSpan('my_root').start()
    def child = tracer.buildSpan('my_child').asChildOf(root).start()

    expect:
    root.context().tags.containsKey('only_root')
    !child.context().tags.containsKey('only_root')

    cleanup:
    child.finish()
    root.finish()
    tracer.close()
  }

  def "priority sampling when span finishes"() {
    given:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()

    when:
    def span = tracer.buildSpan("operation").start()
    span.finish()
    writer.waitForTraces(1)

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP

    cleanup:
    tracer.close()
  }

  def "priority sampling set when child span complete"() {
    given:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()

    when:
    def root = tracer.buildSpan("operation").start()
    def child = tracer.buildSpan('my_child').asChildOf(root).start()
    root.finish()

    then:
    root.getSamplingPriority() == null

    when:
    child.finish()
    writer.waitForTraces(1)

    then:
    root.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    child.getSamplingPriority() == root.getSamplingPriority()

    cleanup:
    tracer.close()
  }

  def "span priority set when injecting"() {
    given:
    injectSysConfig("writer.type", "LoggingWriter")
    def tracer = tracerBuilder().build()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan("operation").start()
    def child = tracer.buildSpan('my_child').asChildOf(root).start()
    tracer.inject(child.context(), carrier, setter)

    then:
    root.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(PrioritySampling.SAMPLER_KEEP))

    cleanup:
    child.finish()
    root.finish()
    tracer.close()
  }

  def "span priority only set after first injection"() {
    given:
    def sampler = new ControllableSampler()
    def tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan("operation").start()
    def child = tracer.buildSpan('my_child').asChildOf(root).start()
    tracer.inject(child.context(), carrier, setter)

    then:
    root.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(PrioritySampling.SAMPLER_KEEP))

    when:
    sampler.nextSamplingPriority = PrioritySampling.SAMPLER_DROP
    def child2 = tracer.buildSpan('my_child2').asChildOf(root).start()
    tracer.inject(child2.context(), carrier, setter)

    then:
    root.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    child.getSamplingPriority() == root.getSamplingPriority()
    child2.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(PrioritySampling.SAMPLER_KEEP))

    cleanup:
    child.finish()
    child2.finish()
    root.finish()
    tracer.close()
  }

  def "injection doesn't override set priority"() {
    given:
    def sampler = new ControllableSampler()
    def tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build()
    def setter = Mock(AgentPropagation.Setter)
    def carrier = new Object()

    when:
    def root = tracer.buildSpan("operation").start()
    def child = tracer.buildSpan('my_child').asChildOf(root).start()
    child.setSamplingPriority(PrioritySampling.USER_DROP)
    tracer.inject(child.context(), carrier, setter)

    then:
    root.getSamplingPriority() == PrioritySampling.USER_DROP
    child.getSamplingPriority() == root.getSamplingPriority()
    1 * setter.set(carrier, DatadogHttpCodec.SAMPLING_PRIORITY_KEY, String.valueOf(PrioritySampling.USER_DROP))

    cleanup:
    child.finish()
    root.finish()
    tracer.close()
  }
}

class ControllableSampler<T extends CoreSpan<T>> implements Sampler<T>, PrioritySampler<T> {
  protected int nextSamplingPriority = PrioritySampling.SAMPLER_KEEP

  @Override
  void setSamplingPriority(T span) {
    span.setSamplingPriority(nextSamplingPriority, SamplingMechanism.DEFAULT)
  }

  @Override
  boolean sample(T span) {
    return true
  }
}
