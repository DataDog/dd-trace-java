import datadog.trace.agent.test.AgentTestRunner
import org.apache.spark.sql.SparkSession

class SparkTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
  }

  def "generate application spans"() {
    setup:
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local")
      .appName("Sample Spark App")
      .getOrCreate()

    sparkSession.stop()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "spark.application"
          resourceName "spark.application"
          spanType "spark"
          errored false
          parent()
        }
      }
    }
  }
}
