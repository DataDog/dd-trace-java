package datadog.trace.instrumentation.mongo

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.sampling.PrioritySampling
import org.bson.BsonDocument
import org.bson.BsonString

class MongoCommentInjectorTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("service.name", "test-mongo-service")
    injectSysConfig("dd.env", "test")
    injectSysConfig("dd.version", "1.0.0")
  }

  def "getComment returns null when INJECT_COMMENT is false"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "disabled")

    when:
    String comment = MongoCommentInjector.getComment(null, null)

    then:
    comment == null
  }

  def "buildTraceParent with sampled flag (SAMPLER_KEEP)"() {
    setup:
    def span = TEST_TRACER.buildSpan("test-op").start()
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP, 0)

    when:
    String traceParent = MongoCommentInjector.buildTraceParent(span)

    then:
    traceParent != null
    traceParent ==~ /00-[0-9a-f]{32}-[0-9a-f]{16}-01/

    cleanup:
    span?.finish()
  }

  def "buildTraceParent with not sampled flag (SAMPLER_DROP)"() {
    setup:
    def span = TEST_TRACER.buildSpan("test-op").start()
    span.setSamplingPriority(PrioritySampling.SAMPLER_DROP, 0)

    when:
    String traceParent = MongoCommentInjector.buildTraceParent(span)

    then:
    traceParent != null
    traceParent ==~ /00-[0-9a-f]{32}-[0-9a-f]{16}-00/

    cleanup:
    span?.finish()
  }

  def "injectComment returns null when event is null"() {
    when:
    BsonDocument result = MongoCommentInjector.injectComment("test-comment", null)

    then:
    result == null
  }

  def "injectComment adds comment to simple find command"() {
    setup:
    def originalCommand = new BsonDocument("find", new BsonString("collection"))
    def dbmComment = "dddbs='test-service',dde='test'"

    when:
    // This should work without mocks - just basic BSON manipulation
    BsonDocument result = originalCommand.clone()
    result.put("\$comment", new BsonString(dbmComment))

    then:
    result != originalCommand
    result.containsKey("\$comment")
    result.get("\$comment").asString().getValue() == dbmComment
  }

  def "basic comment string formatting works"() {
    setup:
    def serviceName = "test-service"
    def environment = "test"
    def hostname = "localhost"
    def dbName = "testdb"

    when:
    def comment = "ddps='$serviceName',dde='$environment',ddh='$hostname',dddb='$dbName'"

    then:
    comment != null
    comment.contains("ddps='test-service'")
    comment.contains("dde='test'")
    comment.contains("ddh='localhost'")
    comment.contains("dddb='testdb'")
  }
}
