package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.AgentTestRunner
import org.apache.spark.sql.SparkSession
import spock.lang.Unroll

@Unroll
abstract class AbstractSpark32SqlTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
  }

  def "compute a GROUP BY sql query plan"() {
    def sparkSession = SparkSession.builder()
      .config("spark.master", "local[2]")
      .config("spark.sql.shuffle.partitions", "2")
      .getOrCreate()

    def df = AbstractSpark24SqlTest.generateSampleDataframe(sparkSession)
    df.createOrReplaceTempView("test")

    sparkSession.sql("""
      SELECT string_col, avg(double_col)
      FROM test
      GROUP BY string_col
      ORDER BY avg(double_col) DESC
    """).show()
    sparkSession.stop()

    def firstStagePlan = """{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":3,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (1)","metrics":[{"duration":-?\\d+,"type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"number of output rows":3,"type":"sum"},{"peak memory":-?\\d+,"type":"size"},{"time in aggregation build":-?\\d+,"type":"timing"}],"children":[{"node":"LocalTableScan","metrics":[{"number of output rows":3,"type":"sum"}]}]}]}]}"""
    def secondStagePlan = """{"node":"WholeStageCodegen (2)","metrics":[{"duration":-?\\d+,"type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"avg hash probe bucket list iters":-?\\d+,"type":"average"},{"number of output rows":2,"type":"sum"},{"peak memory":-?\\d+,"type":"size"},{"time in aggregation build":-?\\d+,"type":"timing"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"fetch wait time":-?\\d+,"type":"timing"},{"local blocks read":-?\\d+,"type":"sum"},{"local bytes read":-?\\d+,"type":"size"},{"records read":3,"type":"sum"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":3,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}]}]}]}]}]}]}"""
    def thirdStagePlan = """{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":2,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (2)","metrics":[{"duration":-?\\d+,"type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"avg hash probe bucket list iters":-?\\d+,"type":"average"},{"number of output rows":-?\\d+,"type":"sum"},{"peak memory":-?\\d+,"type":"size"},{"time in aggregation build":-?\\d+,"type":"timing"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"fetch wait time":-?\\d+,"type":"timing"},{"local blocks read":-?\\d+,"type":"sum"},{"local bytes read":-?\\d+,"type":"size"},{"records read":-?\\d+,"type":"sum"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":-?\\d+,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}]}]}]}]}]}]}]}"""
    def fourthStagePlan = """{"node":"WholeStageCodegen (3)","metrics":[{"duration":-?\\d+,"type":"timing"}],"children":[{"node":"Project","children":[{"node":"Sort","metrics":[{"peak memory":-?\\d+,"type":"size"},{"sort time":-?\\d+,"type":"timing"},{"spill size":-?\\d+,"type":"size"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"fetch wait time":-?\\d+,"type":"timing"},{"local blocks read":-?\\d+,"type":"sum"},{"local bytes read":-?\\d+,"type":"size"},{"records read":2,"type":"sum"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":2,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}]}]}]}]}]}]}]}"""

    firstStagePlan = AbstractSpark24SqlTest.escapeJsonString(firstStagePlan)
    secondStagePlan = AbstractSpark24SqlTest.escapeJsonString(secondStagePlan)
    thirdStagePlan = AbstractSpark24SqlTest.escapeJsonString(thirdStagePlan)
    fourthStagePlan = AbstractSpark24SqlTest.escapeJsonString(fourthStagePlan)

    expect:
    assertTraces(1) {
      trace(10) {
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
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(4))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$thirdStagePlan/
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(6))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$secondStagePlan/
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(8))
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

    def df = AbstractSpark24SqlTest.generateSampleDataframe(sparkSession)
    df.createOrReplaceTempView("test")

    sparkSession.sql("""
      SELECT *
      FROM test a
      JOIN test b ON a.string_col = b.string_col
      WHERE a.double_col > 1.2
    """).count()

    sparkSession.stop()

    def firstStagePlan = """{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":-?\\d+,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}],"children":[{"node":"LocalTableScan","metrics":[{"number of output rows":-?\\d+,"type":"sum"}]}]}"""
    def secondStagePlan = """{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":-?\\d+,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}],"children":[{"node":"LocalTableScan","metrics":[{"number of output rows":-?\\d+,"type":"sum"}]}]}"""
    def thirdStagePlan = """{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":1,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (3)","metrics":[{"duration":-?\\d+,"type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"number of output rows":1,"type":"sum"},{"time in aggregation build":-?\\d+,"type":"timing"}],"children":[{"node":"Project","children":[{"node":"SortMergeJoin","metrics":[{"number of output rows":-?\\d+,"type":"sum"}],"children":[{"node":"InputAdapter","children":[{"node":"WholeStageCodegen (1)","metrics":[{"duration":-?\\d+,"type":"timing"}],"children":[{"node":"Sort","metrics":[{"peak memory":-?\\d+,"type":"size"},{"sort time":-?\\d+,"type":"timing"},{"spill size":-?\\d+,"type":"size"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"fetch wait time":-?\\d+,"type":"timing"},{"local blocks read":-?\\d+,"type":"sum"},{"local bytes read":-?\\d+,"type":"size"},{"records read":-?\\d+,"type":"sum"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":-?\\d+,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}]}]}]}]}]}]}]},{"node":"InputAdapter","children":[{"node":"WholeStageCodegen (2)","metrics":[{"duration":-?\\d+,"type":"timing"}],"children":[{"node":"Sort","metrics":[{"peak memory":-?\\d+,"type":"size"},{"sort time":-?\\d+,"type":"timing"},{"spill size":-?\\d+,"type":"size"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"fetch wait time":-?\\d+,"type":"timing"},{"local blocks read":-?\\d+,"type":"sum"},{"local bytes read":-?\\d+,"type":"size"},{"records read":-?\\d+,"type":"sum"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":-?\\d+,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}]}]}]}]}]}]}]}]}]}]}]}]}"""
    def fourthStagePlan = """{"node":"WholeStageCodegen (4)","metrics":[{"duration":-?\\d+,"type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"number of output rows":-?\\d+,"type":"sum"},{"time in aggregation build":-?\\d+,"type":"timing"}],"children":[{"node":"InputAdapter","children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":-?\\d+,"type":"size"},{"fetch wait time":-?\\d+,"type":"timing"},{"local blocks read":-?\\d+,"type":"sum"},{"local bytes read":-?\\d+,"type":"size"},{"records read":-?\\d+,"type":"sum"},{"shuffle bytes written":-?\\d+,"type":"size"},{"shuffle records written":-?\\d+,"type":"sum"},{"shuffle write time":-?\\d+,"type":"nsTiming"}]}]}]}]}]}"""

    firstStagePlan = AbstractSpark24SqlTest.escapeJsonString(firstStagePlan)
    secondStagePlan = AbstractSpark24SqlTest.escapeJsonString(secondStagePlan)
    thirdStagePlan = AbstractSpark24SqlTest.escapeJsonString(thirdStagePlan)
    fourthStagePlan = AbstractSpark24SqlTest.escapeJsonString(fourthStagePlan)

    expect:
    assertTraces(1) {
      trace(10) {
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
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(4))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$thirdStagePlan/
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(6))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$secondStagePlan/
        }
        span {
          operationName "spark.job"
          spanType "spark"
          childOf(span(1))
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(8))
          assert span.tags["_dd.spark.sql_plan"] ==~ /$firstStagePlan/
        }
      }
    }
  }
}
