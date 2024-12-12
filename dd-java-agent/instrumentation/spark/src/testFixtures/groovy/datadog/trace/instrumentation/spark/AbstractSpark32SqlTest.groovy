package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.test.util.Flaky
import org.apache.spark.sql.SparkSession

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

    def firstStagePlan = """{"node":"Exchange","nodeId":-1478732333,"metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":3,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (1)","nodeId":-2095665476,"metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","nodeId":1128016273,"metrics":[{"number of output rows":3,"type":"sum"},{"peak memory":"any","type":"size"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"LocalTableScan","nodeId":1632930767,"metrics":[{"number of output rows":3,"type":"sum"}]}]}]}]}"""
    def secondStagePlan = """{"node":"WholeStageCodegen (2)","nodeId":-2095665445,"metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","nodeId":126020943,"metrics":[{"avg hash probe bucket list iters":"any","type":"average"},{"number of output rows":2,"type":"sum"},{"peak memory":"any","type":"size"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"InputAdapter","nodeId":180293,"children":[{"node":"AQEShuffleRead","nodeId":859212759,"metrics":[],"children":[{"node":"ShuffleQueryStage","nodeId":1722565407,"children":[{"node":"Exchange","nodeId":-1478732333,"metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":3,"type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":3,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}"""
    def thirdStagePlan = """{"node":"Exchange","nodeId":1918740223,"metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":2,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (2)","nodeId":-2095665445,"metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","nodeId":126020943,"metrics":[{"avg hash probe bucket list iters":"any","type":"average"},{"number of output rows":"any","type":"sum"},{"peak memory":"any","type":"size"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"InputAdapter","nodeId":180293,"children":[{"node":"AQEShuffleRead","nodeId":859212759,"metrics":[],"children":[{"node":"ShuffleQueryStage","nodeId":1722565407,"children":[{"node":"Exchange","nodeId":-1478732333,"metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":"any","type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}]}"""
    def fourthStagePlan = """{"node":"WholeStageCodegen (3)","nodeId":-2095665414,"metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"Project","nodeId":-437821613,"children":[{"node":"Sort","nodeId":-1103360848,"metrics":[{"peak memory":"any","type":"size"},{"sort time":"any","type":"timing"},{"spill size":"any","type":"size"}],"children":[{"node":"InputAdapter","nodeId":180293,"children":[{"node":"AQEShuffleRead","nodeId":859212759,"metrics":[],"children":[{"node":"ShuffleQueryStage","nodeId":1722565408,"children":[{"node":"Exchange","nodeId":1918740223,"metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":2,"type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":2,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}]}"""

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
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[4]"
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
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[0]"
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
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[0]"
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
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
        }
      }
    }
  }

  @Flaky("https://github.com/DataDog/dd-trace-java/issues/6957")
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

    def firstStagePlan = """{"node":"Exchange","nodeId":"any","metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"LocalTableScan","nodeId":"any","metrics":[{"number of output rows":"any","type":"sum"}]}]}"""
    def secondStagePlan = """{"node":"Exchange","nodeId":"any","metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"LocalTableScan","nodeId":"any","metrics":[{"number of output rows":"any","type":"sum"}]}]}"""
    def thirdStagePlan = """{"node":"Exchange","nodeId":-178148375,"metrics":[{"data size":"any","type":"size"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":1,"type":"sum"},{"shuffle write time":"any","type":"nsTiming"}],"children":[{"node":"WholeStageCodegen (3)","nodeId":-2095665414,"metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","nodeId":-879128980,"metrics":[{"number of output rows":1,"type":"sum"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"Project","nodeId":1355342585,"children":[{"node":"SortMergeJoin","nodeId":-827855373,"metrics":[{"number of output rows":"any","type":"sum"}],"children":[{"node":"InputAdapter","nodeId":180293,"children":[{"node":"WholeStageCodegen (1)","nodeId":-2095665476,"metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"Sort","nodeId":-1716348417,"metrics":[{"peak memory":"any","type":"size"},{"sort time":"any","type":"timing"},{"spill size":"any","type":"size"}],"children":[{"node":"InputAdapter","nodeId":180293,"children":[{"node":"AQEShuffleRead","nodeId":859212759,"metrics":[],"children":[{"node":"ShuffleQueryStage","nodeId":1722565407,"children":[{"node":"Exchange","nodeId":1119987703,"metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":"any","type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}]},{"node":"InputAdapter","nodeId":180293,"children":[{"node":"WholeStageCodegen (2)","nodeId":-2095665445,"metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"Sort","nodeId":505172550,"metrics":[{"peak memory":"any","type":"size"},{"sort time":"any","type":"timing"},{"spill size":"any","type":"size"}],"children":[{"node":"InputAdapter","nodeId":180293,"children":[{"node":"AQEShuffleRead","nodeId":859212759,"metrics":[],"children":[{"node":"ShuffleQueryStage","nodeId":1722565408,"children":[{"node":"Exchange","nodeId":-26878534,"metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":"any","type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}]}]}]}]}]}]}]}"""
    def fourthStagePlan = """{"node":"WholeStageCodegen (4)","nodeId":-2095665383,"metrics":[{"duration":"any","type":"timing"}],"children":[{"node":"HashAggregate","nodeId":724815342,"metrics":[{"number of output rows":"any","type":"sum"},{"time in aggregation build":"any","type":"timing"}],"children":[{"node":"InputAdapter","nodeId":180293,"children":[{"node":"ShuffleQueryStage","nodeId":1722565409,"children":[{"node":"Exchange","nodeId":-178148375,"metrics":[{"data size":"any","type":"size"},{"fetch wait time":"any","type":"timing"},{"local blocks read":"any","type":"sum"},{"local bytes read":"any","type":"size"},{"records read":"any","type":"sum"},{"shuffle bytes written":"any","type":"size"},{"shuffle records written":"any","type":"sum"},{"shuffle write time":"any","type":"nsTiming"}]}]}]}]}]}"""

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
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[4]"
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
          assert ["[0, 1]", "[1, 0]"].contains(span.tags["_dd.spark.sql_parent_stage_ids"])
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
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
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
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
        }
      }
    }
  }
}
