import datadog.trace.instrumentation.spark.AbstractSparkListenerTest
import datadog.trace.instrumentation.spark.DatadogSpark212Listener
import org.apache.spark.SparkConf

class SparkListenerTest extends AbstractSparkListenerTest {
  @Override
  protected DatadogSpark212Listener getTestDatadogSparkListener() {
    def conf = new SparkConf()
    return new DatadogSpark212Listener(conf, "some_app_id", "some_version")
  }

  @Override
  protected DatadogSpark212Listener getTestDatadogSparkListener(SparkConf conf) {
    return new DatadogSpark212Listener(conf, "some_app_id", "some_version")
  }
}
