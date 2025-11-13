import datadog.trace.core.database.SharedDBCommenter
import spock.lang.Specification

class MongoDBMCommentTest extends Specification {
  def "SharedDBCommenter builds MongoDB comment correctly"() {
    when:
    String comment = SharedDBCommenter.buildComment(
      "test-mongo-service",
      "mongo",
      "localhost",
      "testdb",
      "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
      )

    then:
    comment != null
    comment.contains("ddps='")
    comment.contains("dddbs='test-mongo-service'")
    comment.contains("ddh='localhost'")
    comment.contains("dddb='testdb'")
    comment.contains("traceparent='00-1234567890abcdef1234567890abcdef-1234567890abcdef-01'")
  }

  def "SharedDBCommenter detects existing trace comments"() {
    given:
    String existingComment = "ddps='service1',dddbs='mongo-service',ddh='host'"

    expect:
    SharedDBCommenter.containsTraceComment(existingComment) == true
    SharedDBCommenter.containsTraceComment("some other comment") == false
    SharedDBCommenter.containsTraceComment("") == false
  }

  def "SharedDBCommenter with valid values produces expected comment format"() {
    when:
    String comment = SharedDBCommenter.buildComment(
      "test-service",
      "mongo",
      "test-host",
      "test-db",
      "00-test-trace-test-span-01"
      )

    then:
    comment != null
    comment.contains("dddbs='test-service'")
    comment.contains("ddh='test-host'")
    comment.contains("dddb='test-db'")
    comment.contains("traceparent='00-test-trace-test-span-01'")
  }
}
