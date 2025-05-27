import datadog.environment.JavaVirtualMachine
import datadog.trace.instrumentation.spark.AbstractSpark32SqlTest
import spock.lang.IgnoreIf

@IgnoreIf(reason="Hadoop 3.3.1 (used by spark 3.2) does not support IBM java https://issues.apache.org/jira/browse/HADOOP-17971", value = {
  JavaVirtualMachine.isIbm8()
})
class Spark32SqlTest extends AbstractSpark32SqlTest {}
