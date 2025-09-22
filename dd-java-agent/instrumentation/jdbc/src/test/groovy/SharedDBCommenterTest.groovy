import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.jdbc.SharedDBCommenter

class SharedDBCommenterTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("service.name", "test-service")
    injectSysConfig("dd.env", "test-env")
    injectSysConfig("dd.version", "1.0.0")
  }

  def "buildComment generates expected format for MongoDB"() {
    when:
    String comment = SharedDBCommenter.buildComment(
      "my-db-service", "mongodb", "localhost", "testdb", null
      )

    then:
    comment != null
    comment.contains("ddps='test-service'")
    comment.contains("dddbs='my-db-service'")
    comment.contains("dde='test-env'")
    comment.contains("ddpv='1.0.0'")
    !comment.contains("traceparent")
  }

  def "buildComment includes traceparent when provided"() {
    when:
    String traceParent = "00-1234567890123456789012345678901234-9876543210987654-01"
    String comment = SharedDBCommenter.buildComment(
      "my-db-service", "mongodb", "localhost", "testdb", traceParent
      )

    then:
    comment != null
    comment.contains("ddps='test-service'")
    comment.contains("dddbs='my-db-service'")
    comment.contains("traceparent='$traceParent'")
  }

  def "buildComment handles null values gracefully"() {
    when:
    String comment = SharedDBCommenter.buildComment(
      dbService, dbType, hostname, dbName, traceParent
      )

    then:
    comment != null || expectedNull

    where:
    dbService | dbType    | hostname  | dbName | traceParent | expectedNull
    null      | "mongodb" | "host"    | "db"   | null        | false
    ""        | "mongodb" | "host"    | "db"   | null        | false
    "service" | "mongodb" | null      | "db"   | null        | false
    "service" | "mongodb" | ""        | "db"   | null        | false
    "service" | "mongodb" | "host"    | null   | null        | false
    "service" | "mongodb" | "host"    | ""     | null        | false
  }

  def "buildComment includes hostname when provided"() {
    when:
    String comment = SharedDBCommenter.buildComment(
      "my-service", "mongodb", "prod-host", "mydb", null
      )

    then:
    comment != null
    comment.contains("ddh='prod-host'")
    comment.contains("dddb='mydb'")
  }

  def "containsTraceComment detects DD fields correctly"() {
    when:
    boolean hasComment = SharedDBCommenter.containsTraceComment(commentContent)

    then:
    hasComment == expected

    where:
    commentContent                                             | expected
    "ddps='service',dddbs='db'"                                | true
    "dde='env',ddpv='1.0'"                                     | true
    "traceparent='00-123-456-01'"                              | true
    "user comment"                                             | false
    ""                                                         | false
    "some other comment with ddps but not the right format"    | false
    "ddps='test',dddbs='db',dde='env'"                         | true
    "prefix ddps='service' suffix"                             | true
  }

  def "buildComment escapes special characters"() {
    when:
    String comment = SharedDBCommenter.buildComment(
      "service with spaces", "mongodb", "host'with'quotes", "db&name", null
      )

    then:
    comment != null
    comment.contains("dddbs='service+with+spaces'")
    comment.contains("ddh='host%27with%27quotes'")
    comment.contains("dddb='db%26name'")
  }

  def "buildComment returns null when no meaningful content"() {
    setup:
    // Configure empty environment
    injectSysConfig("service.name", "")
    injectSysConfig("dd.env", "")
    injectSysConfig("dd.version", "")

    when:
    String comment = SharedDBCommenter.buildComment("", "mongodb", "", "", null)

    then:
    // Even with empty values, buildComment should return something
    // as the implementation always returns sb.toString() unless length is 0
    comment == null || comment.isEmpty()
  }

  def "buildComment works with different database types"() {
    when:
    String comment = SharedDBCommenter.buildComment(
      "my-service", dbType, "localhost", "testdb", null
      )

    then:
    comment != null
    comment.contains("ddps='test-service'")
    comment.contains("dddbs='my-service'")

    where:
    dbType << ["mongodb", "mysql", "postgresql", "oracle"]
  }

  def "buildComment format matches expected pattern"() {
    when:
    String comment = SharedDBCommenter.buildComment(
      "test-db", "mongodb", "host", "db", "00-trace-span-01"
      )

    then:
    comment != null
    // Comment should be comma-separated key=value pairs
    def parts = comment.split(",")
    parts.size() >= 3
    parts.each { part ->
      assert part.contains("=")
      assert part.contains("'")
    }
  }
}
