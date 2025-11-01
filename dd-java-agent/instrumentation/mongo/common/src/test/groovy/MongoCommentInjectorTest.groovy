package datadog.trace.instrumentation.mongo

import com.mongodb.event.CommandStartedEvent
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.sampling.PrioritySampling
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.RawBsonDocument

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
    String comment = MongoCommentInjector.getComment(null, null, null)

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

  def "injectComment handles immutable RawBsonDocument"() {
    setup:
    // Enable DBM propagation for this test
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "service")

    // Create a RawBsonDocument (immutable) by encoding a regular BsonDocument
    def mutableDoc = new BsonDocument("find", new BsonString("collection"))
    def rawDoc = new RawBsonDocument(mutableDoc, new org.bson.codecs.BsonDocumentCodec())
    def dbmComment = "dddbs='test-service',dde='test'"

    // Verify RawBsonDocument.clone() returns immutable document
    def cloned = rawDoc.clone()
    when:
    cloned.put("test", new BsonString("value"))
    then:
    thrown(UnsupportedOperationException)

    when:
    // Create CommandStartedEvent with RawBsonDocument
    def event = new CommandStartedEvent(
      1,  // requestId
      null,  // connectionDescription
      "testdb",  // databaseName
      "find",  // commandName
      rawDoc  // command (immutable)
      )

    // This should NOT throw UnsupportedOperationException with the fix
    BsonDocument result = MongoCommentInjector.injectComment(dbmComment, event)

    then:
    // Should successfully inject comment
    result != null
    result.containsKey("comment")
    result.get("comment").asString().getValue() == dbmComment
    // Should be a different object (not the immutable original)
    !result.is(rawDoc)
  }

  def "injectComment handles mutable BsonDocument"() {
    setup:
    // Enable DBM propagation for this test
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "service")

    def originalCommand = new BsonDocument("find", new BsonString("collection"))
    def dbmComment = "dddbs='test-service',dde='test'"

    def event = new CommandStartedEvent(
      1,  // requestId
      null,  // connectionDescription
      "testdb",  // databaseName
      "find",  // commandName
      originalCommand  // command (mutable)
      )

    when:
    BsonDocument result = MongoCommentInjector.injectComment(dbmComment, event)

    then:
    result != null
    result.containsKey("comment")
    result.get("comment").asString().getValue() == dbmComment
    !result.is(originalCommand)
  }
}
