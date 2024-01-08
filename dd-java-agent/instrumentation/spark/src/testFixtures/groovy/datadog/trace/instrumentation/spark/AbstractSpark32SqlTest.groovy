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

    def firstStagePlan = """{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":3,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (1)","metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"number of output rows":3,"type":"sum"},{"peak memory":"any","type":"size"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"LocalTableScan","metrics":[{"number of output rows":3,"type":"sum"}]}]}]}]}"""
    def secondStagePlan = """{"node":"WholeStageCodegen (2)","metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"avg hash probe bucket list iters":"any","type":"average"},{"number of output rows":2,"type":"sum"},{"peak memory":"any","type":"size"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":3,"type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":3,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}"""
    def thirdStagePlan = """{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":2,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (2)","metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"avg hash probe bucket list iters":"any","type":"average"},{"number of output rows":"any","type":"sum"},{"peak memory":"any","type":"size"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":"any","type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}]}"""
    def fourthStagePlan = """{"node":"WholeStageCodegen (3)","metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"Project","children":[{"node":"Sort","metrics":[{"peak memory":"any","type":"size"},{"sort time":"any","type":"timing"},{"spill size":"any","type":"size"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":2,"type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":2,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}]}"""

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
          AbstractSpark24SqlTest.assertStringSQLPlanEquals(fourthStagePlan, span.tags["_dd.spark.sql_plan"].toString())
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
          AbstractSpark24SqlTest.assertStringSQLPlanEquals(thirdStagePlan, span.tags["_dd.spark.sql_plan"].toString())
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
          AbstractSpark24SqlTest.assertStringSQLPlanEquals(secondStagePlan, span.tags["_dd.spark.sql_plan"].toString())
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
          AbstractSpark24SqlTest.assertStringSQLPlanEquals(firstStagePlan, span.tags["_dd.spark.sql_plan"].toString())
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

    def firstStagePlan = """{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"LocalTableScan","metrics":[{"number of output rows":"any","type":"sum"}]}]}"""
    def secondStagePlan = """{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"LocalTableScan","metrics":[{"number of output rows":"any","type":"sum"}]}]}"""
    def thirdStagePlan = """{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":1,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (3)","metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"number of output rows":1,"type":"sum"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"Project","children":[{"node":"SortMergeJoin","metrics":[{"number of output rows":"any","type":"sum"}],"children":[{"node":"InputAdapter","children":[{"node":"WholeStageCodegen (1)","metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"Sort","metrics":[{"peak memory":"any","type":"size"},{"sort time":"any","type":"timing"},{"spill size":"any","type":"size"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":"any","type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}]},{"node":"InputAdapter","children":[{"node":"WholeStageCodegen (2)","metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"Sort","metrics":[{"peak memory":"any","type":"size"},{"sort time":"any","type":"timing"},{"spill size":"any","type":"size"}],"children":[{"node":"InputAdapter","children":[{"node":"AQEShuffleRead","metrics":[],"children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":"any","type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}]}]}]}]}]}]}"""
    def fourthStagePlan = """{"node":"WholeStageCodegen (4)","metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","metrics":[{"number of output rows":"any","type":"sum"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"InputAdapter","children":[{"node":"ShuffleQueryStage","children":[{"node":"Exchange","metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":"any","type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}"""

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
          AbstractSpark24SqlTest.assertStringSQLPlanEquals(fourthStagePlan, span.tags["_dd.spark.sql_plan"].toString())
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
          AbstractSpark24SqlTest.assertStringSQLPlanEquals(thirdStagePlan, span.tags["_dd.spark.sql_plan"].toString())
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
          AbstractSpark24SqlTest.assertStringSQLPlanEquals(secondStagePlan, span.tags["_dd.spark.sql_plan"].toString())
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
          AbstractSpark24SqlTest.assertStringSQLPlanEquals(firstStagePlan, span.tags["_dd.spark.sql_plan"].toString())
        }
      }
    }
  }
}
