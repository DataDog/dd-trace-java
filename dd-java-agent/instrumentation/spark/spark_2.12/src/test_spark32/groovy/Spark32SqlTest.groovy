import datadog.trace.api.Platform
import datadog.trace.instrumentation.spark.AbstractSpark32SqlTest
import spock.lang.IgnoreIf
import spock.lang.Unroll

@IgnoreIf(reason="Hadoop 3.3.1 (used by spark 3.2) does not support IBM java https://issues.apache.org/jira/browse/HADOOP-17971", value = {
  Platform.isIbm8()
})
@Unroll
class Spark32SqlTest extends AbstractSpark32SqlTest {}
