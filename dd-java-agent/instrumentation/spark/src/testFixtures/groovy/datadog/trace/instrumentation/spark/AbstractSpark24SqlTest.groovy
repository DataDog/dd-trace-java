package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.AgentTestRunner
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.StructType
import spock.lang.Unroll

@Unroll
abstract class AbstractSpark24SqlTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
  }

  static Dataset<Row> generateSampleDataframe(SparkSession spark) {
    def structType = new StructType()
    structType = structType.add("string_col", "String", false)
    structType = structType.add("double_col", "Double", false)

    def rows = new ArrayList<Row>()
    rows.add(RowFactory.create("first", 1.2d))
    rows.add(RowFactory.create("first", 1.3d))
    rows.add(RowFactory.create("second", 1.6d))
    spark.createDataFrame(rows, structType)
  }

  static String escapeJsonString(String source) {
    for (char c : """{}"[]()""".toCharArray()) {
      source = source.replace(c.toString(), "\\" + c.toString())
    }
    return source
  }

  def "compute a GROUP BY sql query plan"() {
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .getOrCreate()

    def df = generateSampleDataframe(sparkSession)
    df.createOrReplaceTempView("test")

    sparkSession.sql("""
      SELECT string_col, avg(double_col)
      FROM test
      GROUP BY string_col
      ORDER BY avg(double_col) DESC
    """).show()

    sparkSession.stop()

    def firstStagePlan = """{"node":"Exchange","metrics":[{"data size total (min, med, max)":-?\\d+,"type":"size"}],"children":[{"node":"WholeStageCodegen","metrics":[{"duration total (min, med, max)":-?\\d+,"type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"aggregate time total (min, med, max)":-?\\d+,"type":"timing"},{"number of output rows":-?\\d+,"type":"sum"},{"peak memory total (min, med, max)":-?\\d+,"type":"size"}],"children":[{"node":"InputAdapter","children":[{"node":"LocalTableScan","metrics":[{"number of output rows":3,"type":"sum"}]}]}]}]}]}"""
    def secondStagePlan = """{"node":"WholeStageCodegen","metrics":[{"duration total (min, med, max)":-?\\d+,"type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"aggregate time total (min, med, max)":-?\\d+,"type":"timing"},{"avg hash probe (min, med, max)":-?\\d+,"type":"average"},{"number of output rows":2,"type":"sum"},{"peak memory total (min, med, max)":-?\\d+,"type":"size"}],"children":[{"node":"InputAdapter"}]}]}"""

    firstStagePlan = escapeJsonString(firstStagePlan)
    secondStagePlan = escapeJsonString(secondStagePlan)

    expect:
    assertTraces(1) {
      trace(5) {
        span {
          operationName "spark.application"
          spanType "spark"
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$secondStagePlan/
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$firstStagePlan/
        }
      }
    }
  }

  def "compute a JOIN sql query plan"() {
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.sql.autoBroadcastJoinThreshold", "-1")
      .getOrCreate()

    def df = generateSampleDataframe(sparkSession)
    df.createOrReplaceTempView("test")

    sparkSession.sql("""
      SELECT *
      FROM test a
      JOIN test b ON a.string_col = b.string_col
      WHERE a.double_col > 1.2
    """).count()

    sparkSession.stop()

    def firstStagePlan = """{"node":"Exchange","metrics":[{"data size total (min, med, max)":-?\\d+,"type":"size"}],"children":[{"node":"LocalTableScan","metrics":[{"number of output rows":-?\\d+,"type":"sum"}]}]}"""
    def secondStagePlan = """{"node":"Exchange","metrics":[{"data size total (min, med, max)":-?\\d+,"type":"size"}],"children":[{"node":"LocalTableScan","metrics":[{"number of output rows":-?\\d+,"type":"sum"}]}]}"""
    def thirdStagePlan = """{"node":"Exchange","metrics":[{"data size total (min, med, max)":-?\\d+,"type":"size"}],"children":[{"node":"WholeStageCodegen","metrics":[{"duration total (min, med, max)":-?\\d+,"type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"aggregate time total (min, med, max)":-?\\d+,"type":"timing"},{"number of output rows":-?\\d+,"type":"sum"}],"children":[{"node":"Project","children":[{"node":"SortMergeJoin","metrics":[{"number of output rows":-?\\d+,"type":"sum"}],"children":[{"node":"InputAdapter","children":[{"node":"WholeStageCodegen","metrics":[{"duration total (min, med, max)":-?\\d+,"type":"timing"}],"children":[{"node":"Sort","metrics":[{"peak memory total (min, med, max)":-?\\d+,"type":"size"},{"sort time total (min, med, max)":-?\\d+,"type":"timing"}],"children":[{"node":"InputAdapter"}]}]}]},{"node":"InputAdapter","children":[{"node":"WholeStageCodegen","metrics":[{"duration total (min, med, max)":-?\\d+,"type":"timing"}],"children":[{"node":"Sort","metrics":[{"peak memory total (min, med, max)":-?\\d+,"type":"size"}],"children":[{"node":"InputAdapter"}]}]}]}]}]}]}]}]}"""
    def fourthStagePlan = """.*{"node":"HashAggregate","metrics":[{"number of output rows":1,"type":"sum"}],"children":[{"node":"InputAdapter"}]}.*"""

    firstStagePlan = escapeJsonString(firstStagePlan)
    secondStagePlan = escapeJsonString(secondStagePlan)
    thirdStagePlan = escapeJsonString(thirdStagePlan)
    fourthStagePlan = escapeJsonString(fourthStagePlan)

    expect:
    assertTraces(1) {
      trace(7) {
        span {
          operationName "spark.application"
          spanType "spark"
        }
        span {
          operationName "spark.sql"
          spanType "spark"
          childOf(span(0))
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$fourthStagePlan/
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$thirdStagePlan/
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$secondStagePlan/
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$firstStagePlan/
        }
      }
    }
  }
}
