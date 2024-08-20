package datadog.trace.instrumentation.spark

import datadog.trace.api.DDSpanId
import org.apache.spark.SparkConf
import spock.lang.Specification

class OpenlineageParentContextTest extends Specification {
  def "should create empty context if no OpenLineage parent context present" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains("spark.openlineage.parentRunId") >> false

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    !parentContext.isPresent()
  }

  def "should create none empty context if OpenLineage parent context is present" () {
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

  def "should create empty context if OpenLineage parent context is partially present" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> false
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> false

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    !parentContext.isPresent()
  }

  def "should create empty context if OpenLineage parent runId is empty" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> true
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> "default"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> "dag-push-to-s3-spark.upload_to_s3"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> "  "

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    !parentContext.isPresent()
  }
}

