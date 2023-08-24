package datadog.trace.core

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.remoteconfig.state.ParsedConfigKey
import datadog.remoteconfig.state.ProductListener
import datadog.trace.api.Config
import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.sampling.AllSampler
import datadog.trace.common.sampling.PrioritySampler
import datadog.trace.common.sampling.RateByServiceTraceSampler
import datadog.trace.common.sampling.Sampler
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.core.datastreams.DataStreamContextExtractor
import datadog.trace.core.propagation.HttpCodec
import datadog.trace.core.test.DDCoreSpecification
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import spock.lang.Timeout

import java.nio.charset.StandardCharsets

import static datadog.trace.api.config.GeneralConfig.ENV
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME
import static datadog.trace.api.config.GeneralConfig.VERSION
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET
import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING
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
    tracer.initialSampler instanceof RateByServiceTraceSampler
    tracer.writer instanceof DDAgentWriter
    tracer.statsDClient != null && tracer.statsDClient != StatsDClient.NO_OP

    tracer.propagate().injector instanceof HttpCodec.CompoundInjector
    tracer.propagate().extractor instanceof DataStreamContextExtractor

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
    tracer.initialSampler instanceof AllSampler

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

    then:
    tracer.defaultSpanTags == map
    tracer.captureTraceConfig().serviceMapping == map

    cleanup:
    tracer.close()

    where:
    mapString               | map
    "a:one, a:two, a:three" | [a: "three"]
    "a:b,c:d,e:"            | [a: "b", c: "d"]
  }

  def "verify baggage mapping configs on tracer"() {
    setup:
    injectSysConfig(BAGGAGE_MAPPING, mapString)

    when:
    def tracer = tracerBuilder().build()

    then:
    tracer.captureTraceConfig().baggageMapping == map

    cleanup:
    tracer.close()

    where:
    mapString               | map
    "a:one, a:two, a:three" | [a: "three"]
    "a:b,c:d,e:"            | [a: "b", c: "d"]
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

  def "verify configuration polling"() {
    setup:
    def key = ParsedConfigKey.parse("datadog/2/APM_TRACING/config_overrides/config")
    def poller = Mock(ConfigurationPoller)
    def sco = new SharedCommunicationObjects(
      okHttpClient: Mock(OkHttpClient),
      monitoring: Mock(Monitoring),
      agentUrl: HttpUrl.get('https://example.com'),
      featuresDiscovery: Mock(DDAgentFeaturesDiscovery),
      configurationPoller: poller
      )

    def updater

    when:
    def tracer = CoreTracer.builder()
      .sharedCommunicationObjects(sco)
      .pollForTracingConfiguration()
      .build()

    then:
    1 * poller.addListener(Product.APM_TRACING, _ as ProductListener) >> {
      updater = it[1] // capture config updater for further testing
    }
    and:
    tracer.captureTraceConfig().serviceMapping == [:]
    tracer.captureTraceConfig().requestHeaderTags == [:]
    tracer.captureTraceConfig().responseHeaderTags == [:]
    tracer.captureTraceConfig().traceSampleRate == null

    when:
    updater.accept(key, '''
      {
        "lib_config":
        {
          "tracing_service_mapping":
          [{
             "from_key": "foobar",
             "to_name": "bar"
          }, {
             "from_key": "snafu",
             "to_name": "foo"
          }]
          ,
          "tracing_header_tags":
          [{
             "header": "Cookie",
             "tag_name": ""
          }, {
             "header": "Referer",
             "tag_name": "http.referer"
          }, {
             "header": "  Some.Header  ",
             "tag_name": ""
          }, {
             "header": "C!!!ont_____ent----tYp!/!e",
             "tag_name": ""
          }, {
             "header": "this.header",
             "tag_name": "whatever.the.user.wants.this.header"
          }]
          ,
          "tracing_sampling_rate": 0.5
        }
      }
      '''.getBytes(StandardCharsets.UTF_8), null)
    updater.commit()

    then:
    tracer.captureTraceConfig().serviceMapping == ['foobar':'bar', 'snafu':'foo']
    tracer.captureTraceConfig().requestHeaderTags == [
      'cookie':'http.request.headers.cookie',
      'referer':'http.referer',
      'some.header':'http.request.headers.some_header',
      'c!!!ont_____ent----typ!/!e':'http.request.headers.c___ont_____ent----typ_/_e',
      'this.header':'whatever.the.user.wants.this.header'
    ]
    tracer.captureTraceConfig().responseHeaderTags == [
      'cookie':'http.response.headers.cookie',
      'referer':'http.referer',
      'some.header':'http.response.headers.some_header',
      'c!!!ont_____ent----typ!/!e':'http.response.headers.c___ont_____ent----typ_/_e',
      'this.header':'whatever.the.user.wants.this.header'
    ]
    tracer.captureTraceConfig().traceSampleRate == 0.5

    when:
    updater.remove(key, null)
    updater.commit()

    then:
    tracer.captureTraceConfig().serviceMapping == [:]
    tracer.captureTraceConfig().requestHeaderTags == [:]
    tracer.captureTraceConfig().responseHeaderTags == [:]
    tracer.captureTraceConfig().traceSampleRate == null

    cleanup:
    tracer?.close()
  }
}

class ControllableSampler implements Sampler, PrioritySampler {
  protected int nextSamplingPriority = PrioritySampling.SAMPLER_KEEP

  @Override
  <T extends CoreSpan<T>> void setSamplingPriority(T span) {
    span.setSamplingPriority(nextSamplingPriority, SamplingMechanism.DEFAULT)
  }

  @Override
  <T extends CoreSpan<T>> boolean sample(T span) {
    return true
  }
}
