import static datadog.trace.instrumentation.spark.TestSparkComputation.getSparkVersion

import datadog.trace.api.Platform
import datadog.trace.instrumentation.spark.AbstractSparkStructuredStreamingTest
import spock.lang.IgnoreIf

@IgnoreIf(reason="For J9: https://issues.apache.org/jira/browse/HADOOP-18174, for IBM java: https://issues.apache.org/jira/browse/HADOOP-17971", value = {
  Platform.isJ9() || (Platform.isIBMJava() && getSparkVersion().startsWith("3.2"))
})
class SparkStructuredStreamingTest extends AbstractSparkStructuredStreamingTest {}
