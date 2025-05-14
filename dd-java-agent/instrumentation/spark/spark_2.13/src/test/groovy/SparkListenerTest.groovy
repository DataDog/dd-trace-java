import datadog.trace.instrumentation.spark.AbstractSparkListenerTest
import datadog.trace.instrumentation.spark.DatadogSpark213Listener
import org.apache.spark.SparkConf

class SparkListenerTest extends AbstractSparkListenerTest {
  @Override
  protected DatadogSpark213Listener getTestDatadogSparkListener() {
    def conf = new SparkConf()
    return new DatadogSpark213Listener(conf, "some_app_id", "some_version")
  }

  @Override
  protected DatadogSpark213Listener getTestDatadogSparkListener(SparkConf conf) {
    return new DatadogSpark213Listener(conf, "some_app_id", "some_version")
  }
}
