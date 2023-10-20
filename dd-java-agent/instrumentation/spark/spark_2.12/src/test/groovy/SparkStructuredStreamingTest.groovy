import datadog.trace.api.Platform
import datadog.trace.instrumentation.spark.AbstractSparkStructuredStreamingTest
import spock.lang.IgnoreIf

@IgnoreIf(reason="https://issues.apache.org/jira/browse/HADOOP-18174", value = {
  Platform.isJ9()
})
class SparkStructuredStreamingTest extends AbstractSparkStructuredStreamingTest {}
