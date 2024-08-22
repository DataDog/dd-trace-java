package datadog.trace.instrumentation.spark

import datadog.trace.api.DDSpanId
import org.apache.spark.SparkConf
import spock.lang.Specification

class OpenlineageParentContextTest extends Specification {
  def "should create none empty OpenLineageParentContext using SHA-256 for TraceID and root span SpanId if all required fields are present" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> true
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> "default"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> "dag-push-to-s3-spark.upload_to_s3"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> parentRunId

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    parentContext.isPresent()

    parentContext.get().getParentJobNamespace() == "default"
    parentContext.get().getParentJobName() == "dag-push-to-s3-spark.upload_to_s3"
    parentContext.get().getParentRunId() == expectedParentRunId

    parentContext.get().traceId.toLong() == expectedTraceId
    parentContext.get().spanId == DDSpanId.ZERO
    parentContext.get().childRootSpanId == expectedRootSpanId

    where:
    parentRunId                            || expectedParentRunId                    | expectedTraceId      | expectedRootSpanId
    "ad3b6baa-8d88-3b38-8dbe-f06232249a84" || "ad3b6baa-8d88-3b38-8dbe-f06232249a84" | 0xa475569dbce5e6cfL  | 0xa475569dbce5e6cfL
    "ad3b6baa-8d88-3b38-8dbe-f06232249a85" || "ad3b6baa-8d88-3b38-8dbe-f06232249a85" | 0x31da6680bd14991bL  | 0x31da6680bd14991bL
  }

  def "should create empty OpenLineageParentContext if any required field is missing" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> jobNamespacePresent
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> jobNamePresent
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> runIdPresent

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    parentContext.isPresent() == expected

    where:
    jobNamespacePresent | jobNamePresent | runIdPresent || expected
    true                | true           | false        || false
    true                | false          | true         || false
    false               | true           | true         || false
    true                | false          | false        || false
    false               | true           | false        || false
    false               | false          | true         || false
    false               | false          | false        || false
  }

  def "should only generate a non-empty OpenlineageParentContext if parentRunId is a valid UUID" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> true
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> "default"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> "dag-push-to-s3-spark.upload_to_s3"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> runId

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    parentContext.isPresent() == expected

    where:
    runId                                  || expected
    "6afeb6ee-729d-37f7-ad73-b8e6f47ca694" || true
    "  "                                   || false
    "invalid-uuid"                         || false
    "6afeb6ee-729d-37f7-b8e6f47ca694"      || false
    "6AFEB6EE-729D-37F7-AD73-B8E6F47CA694" || true
  }
}

