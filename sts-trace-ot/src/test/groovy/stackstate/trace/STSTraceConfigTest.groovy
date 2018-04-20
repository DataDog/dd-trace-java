package stackstate.trace

import stackstate.opentracing.STSTracer
import stackstate.trace.common.STSTraceConfig
import stackstate.trace.common.sampling.AllSampler
import stackstate.trace.common.writer.ListWriter
import stackstate.trace.common.writer.LoggingWriter
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.contrib.java.lang.system.RestoreSystemProperties
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll
import stackstate.trace.common.writer.STSAgentWriter

import static stackstate.trace.common.STSTraceConfig.*

@Timeout(1)
class STSTraceConfigTest extends Specification {
  @Rule
  public final RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties()
  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  def "verify env override"() {
    setup:
    environmentVariables.set("SOME_RANDOM_ENTRY", "asdf")

    expect:
    System.getenv("SOME_RANDOM_ENTRY") == "asdf"
  }

  def "verify defaults"() {
    when:
    def config = new STSTraceConfig()

    then:
    config.getProperty(SERVICE_NAME) == "unnamed-java-app"
    config.getProperty(WRITER_TYPE) == "STSAgentWriter"
    config.getProperty(AGENT_HOST) == "localhost"
    config.getProperty(AGENT_PORT) == "8126"

    when:
    config = new STSTraceConfig("A different service name")

    then:
    config.getProperty(SERVICE_NAME) == "A different service name"
    config.getProperty(WRITER_TYPE) == "STSAgentWriter"
    config.getProperty(AGENT_HOST) == "localhost"
    config.getProperty(AGENT_PORT) == "8126"
  }

  def "specify overrides via system properties"() {
    when:
    System.setProperty(PREFIX + SERVICE_NAME, "something else")
    System.setProperty(PREFIX + WRITER_TYPE, LoggingWriter.simpleName)
    def tracer = new STSTracer()

    then:
    tracer.serviceName == "something else"
    tracer.writer instanceof LoggingWriter
  }

  def "specify overrides via env vars"() {
    when:
    environmentVariables.set(propToEnvName(PREFIX + SERVICE_NAME), "still something else")
    environmentVariables.set(propToEnvName(PREFIX + WRITER_TYPE), LoggingWriter.simpleName)
    def tracer = new STSTracer()

    then:
    tracer.serviceName == "still something else"
    tracer.writer instanceof LoggingWriter
  }

  @Timeout(5)
  def "sys props override env vars"() {
    when:
    environmentVariables.set(propToEnvName(PREFIX + SERVICE_NAME), "still something else")
    environmentVariables.set(propToEnvName(PREFIX + WRITER_TYPE), ListWriter.simpleName)

    System.setProperty(PREFIX + SERVICE_NAME, "what we actually want")
    System.setProperty(PREFIX + WRITER_TYPE, STSAgentWriter.simpleName)
    System.setProperty(PREFIX + AGENT_HOST, "somewhere")
    System.setProperty(PREFIX + AGENT_PORT, "9999")

    def tracer = new STSTracer()

    then:
    tracer.serviceName == "what we actually want"
    tracer.writer.toString() == "STSAgentWriter { api=STSApi { tracesEndpoint=http://somewhere:9999/v0.3/traces } }"
  }

  def "verify defaults on tracer"() {
    when:
    def tracer = new STSTracer()

    then:
    tracer.serviceName == "unnamed-java-app"
    tracer.sampler instanceof AllSampler
    tracer.writer.toString() == "STSAgentWriter { api=STSApi { tracesEndpoint=http://localhost:8126/v0.3/traces } }"

    tracer.spanContextDecorators.size() == 6
  }

  @Timeout(5)
  @Unroll
  def "verify single override on #source for #key"() {
    when:
    System.setProperty(PREFIX + key, value)
    def tracer = new STSTracer()

    then:
    tracer."$source".toString() == expected

    where:

    source    | key            | value           | expected
    "writer"  | "default"      | "default"       | "STSAgentWriter { api=STSApi { tracesEndpoint=http://localhost:8126/v0.3/traces } }"
    "writer"  | "writer.type"  | "LoggingWriter" | "LoggingWriter { }"
    "writer"  | "agent.host"   | "somethingelse" | "STSAgentWriter { api=STSApi { tracesEndpoint=http://somethingelse:8126/v0.3/traces } }"
    "writer"  | "agent.port"   | "9999"          | "STSAgentWriter { api=STSApi { tracesEndpoint=http://localhost:9999/v0.3/traces } }"
  }

  def "parsing valid string returns a map"() {
    expect:
    STSTraceConfig.parseMap(str) == map

    where:
    str                               | map
    "a:a;"                            | [a: "a;"]
    "a:1, a:2, a:3"                   | [a: "3"]
    "a:b,c:d"                         | [a: "b", c: "d"]
    "key 1!:va|ue_1,"                 | ["key 1!": "va|ue_1"]
    " key1 :value1 ,\t key2:  value2" | [key1: "value1", key2: "value2"]
  }

  def "parsing an invalid string returns an empty map"() {
    expect:
    STSTraceConfig.parseMap(str) == map

    where:
    str         | map
    null        | [:]
    ""          | [:]
    "1"         | [:]
    "a"         | [:]
    "a:"        | [:]
    "a,1"       | [:]
    "in:val:id" | [:]
    "a:b:c:d"   | [:]
    "a:b,c,d"   | [:]
    "!a"        | [:]
  }
}
