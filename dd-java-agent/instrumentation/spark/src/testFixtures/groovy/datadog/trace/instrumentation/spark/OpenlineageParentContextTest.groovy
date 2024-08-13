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
    mockSparkConf.contains("spark.openlineage.parentRunId") >> true
    mockSparkConf.get("spark.openlineage.parentJobNamespace") >> "default"
    mockSparkConf.get("spark.openlineage.parentJobName") >> "dag-push-to-s3-spark.upload_to_s3"
    mockSparkConf.get("spark.openlineage.parentRunId") >> "ad3b6baa-8d88-3b38-8dbe-f06232249a84"

    then:
    Optional<OpenlineageParentContext> parentContext = OpenlineageParentContext.from(mockSparkConf)
    parentContext.isPresent()
    parentContext.get().getParentJobNamespace() == "default"
    parentContext.get().getParentJobName() == "dag-push-to-s3-spark.upload_to_s3"
    parentContext.get().getParentRunId() == "ad3b6baa-8d88-3b38-8dbe-f06232249a84"

    parentContext.get().traceId.toLong() == 4483950461608639218
    parentContext.get().spanId == DDSpanId.ZERO
    parentContext.get().childRootSpanId == 4483950461608639218
  }
}

