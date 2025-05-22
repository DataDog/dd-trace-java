package datadog.trace.instrumentation.spark

import org.apache.spark.SparkConf
import spock.lang.Specification

class OpenlineageParentContextTest extends Specification {
  def "should create OpenLineageParentContext with particular trace id based on root parent id" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_ROOT_PARENT_RUN_ID) >> true
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> "default"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> "dag-push-to-s3-spark.upload_to_s3"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> parentRunId
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_ROOT_PARENT_RUN_ID) >> rootParentRunId

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    parentContext.isPresent()

    parentContext.get().getParentJobNamespace() == "default"
    parentContext.get().getParentJobName() == "dag-push-to-s3-spark.upload_to_s3"
    parentContext.get().getRootParentRunId() == rootParentRunId
    parentContext.get().getParentRunId() == parentRunId

    parentContext.get().traceId.toString() == expectedTraceId
    parentContext.get().spanId.toString() == expectedSpanId

    where:
    rootParentRunId                        | parentRunId                            | expectedTraceId        | expectedSpanId
    "01964820-5280-7674-b04e-82fbed085f39" | "ad3b6baa-8d88-3b38-8dbe-f06232249a84" | "13959090542865903119" | "2903780135964948649"
    "1a1a1a1a-2b2b-3c3c-4d4d-5e5e5e5e5e5e" | "6f6f6f6f-5e5e-4d4d-3c3c-2b2b2b2b2b2b" | "15830118871223350489" | "8020087091656517257"
  }

  def "should create empty OpenLineageParentContext if any required field is missing" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> jobNamespacePresent
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> jobNamePresent
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> runIdPresent
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_ROOT_PARENT_RUN_ID) >> rootParentIdPresent

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    parentContext.isPresent() == expected

    where:
    jobNamespacePresent | jobNamePresent | runIdPresent | rootParentIdPresent  | expected
    true                | true           | true         | false                | false
    true                | true           | false        | false                | false
    true                | true           | true         | false                | false
    true                | true           | false        | true                 | false
    true                | false          | true         | false                | false
    false               | true           | true         | true                 | false
    true                | false          | false        | false                | false
    false               | true           | false        | false                | false
    false               | false          | true         | true                 | false
    false               | false          | false        | false                | false
  }

  def "should only generate a non-empty OpenlineageParentContext if parentRunId is a valid UUID" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_ROOT_PARENT_RUN_ID) >> true
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> "default"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> "dag-push-to-s3-spark.upload_to_s3"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> runId
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_ROOT_PARENT_RUN_ID) >> runId


    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    parentContext.isPresent() == expected

    where:
    runId                                  | expected
    "6afeb6ee-729d-37f7-ad73-b8e6f47ca694" | true
    "  "                                   | false
    "invalid-uuid"                         | false
    "6afeb6ee-729d-37f7-b8e6f47ca694"      | false
    "6AFEB6EE-729D-37F7-AD73-B8E6F47CA694" | true
  }

  def "should only generate a non-empty OpenlineageParentContext if rootParentRunId is a valid UUID" () {
    given:
    SparkConf mockSparkConf = Mock(SparkConf)

    when:
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> true
    mockSparkConf.contains(OpenlineageParentContext.OPENLINEAGE_ROOT_PARENT_RUN_ID) >> true
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAMESPACE) >> "default"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_JOB_NAME) >> "dag-push-to-s3-spark.upload_to_s3"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_PARENT_RUN_ID) >> "6afeb6ee-729d-37f7-ad73-b8e6f47ca694"
    mockSparkConf.get(OpenlineageParentContext.OPENLINEAGE_ROOT_PARENT_RUN_ID) >> rootParentRunId


    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    parentContext.isPresent() == expected

    where:
    rootParentRunId                        | expected
    "6afeb6ee-729d-37f7-ad73-b8e6f47ca694" | true
    "  "                                   | false
    "invalid-uuid"                         | false
    "6afeb6ee-729d-37f7-b8e6f47ca694"      | false
    "6AFEB6EE-729D-37F7-AD73-B8E6F47CA694" | true
  }
}

