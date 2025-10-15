package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.InstrumentationSpecification
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.StructType

abstract class AbstractSpark24SqlTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
    injectSysConfig("dd.integration.openlineage-spark.enabled", "true")
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

  static assertStringSQLPlanIn(ArrayList<String> expectedStrings, String actualString) {
    def jsonSlurper = new JsonSlurper()
    def actual = jsonSlurper.parseText(actualString)

    for (String expectedString : expectedStrings) {
      try {
        def expected = jsonSlurper.parseText(expectedString)
        assertSQLPlanEquals(expected, actual)
        return
      } catch (AssertionError e) {
        System.println("Failed to assert $expectedString, attempting next")
      }
    }

    throw new AssertionError("No matching SQL Plan found for $actualString in $expectedStrings")
  }

  static assertStringSQLPlanEquals(String expectedString, String actualString) {
    System.err.println("Checking if expected $expectedString SQL plan match actual $actualString")

    def jsonSlurper = new JsonSlurper()

    def expected = jsonSlurper.parseText(expectedString)
    def actual = jsonSlurper.parseText(actualString)

    assertSQLPlanEquals(expected, actual)
  }

  // Similar to assertStringSQLPlanEquals, but the actual plan can be a subset of the expected plan
  // This is used for spark 2.4 where the exact SQL plan is not deterministic
  protected static assertStringSQLPlanSubset(String expectedString, String actualString) {
    System.err.println("Checking if expected $expectedString SQL plan is a super set of $actualString")

    def jsonSlurper = new JsonSlurper()

    def expected = jsonSlurper.parseText(expectedString)
    def actual = jsonSlurper.parseText(actualString)

    try {
      assertSQLPlanEquals(expected.children[0], actual)
      return // If is a subset, the test is successful
    }
    catch (AssertionError e) {}

    assertSQLPlanEquals(expected, actual)
  }

  private static assertSQLPlanEquals(Object expected, Object actual) {
    assert expected.node == actual.node
    assert expected.keySet() == actual.keySet()

    // Checking all keys expect children and metrics that are checked after
    expected.keySet().each { key ->
      if (!['children', 'metrics'].contains(key)) {

        // Some metric values will varies between runs
        // In the case, setting the expected value to "any" skips the assertion
        if (expected[key] != "any") {
          assert expected[key] == actual[key]: "$expected does not match $actual"
        }
      }
    }

    // Checking the metrics are the same on both side
    if (expected.metrics == null) {
      assert actual.metrics == null
    } else {
      expected.metrics.sort { it.keySet() }
      actual.metrics.sort { it.keySet() }

      [expected.metrics, actual.metrics].transpose().each { metricPair ->
        def expectedMetric = metricPair[0]
        def actualMetric = metricPair[1]

        assert expectedMetric.size() == actualMetric.size(): "$expected does not match $actual"

        // Each metric is a dict { "metric_name": "metric_value", "type": "metric_type" }
        expectedMetric.each { key, expectedValue ->
          assert actualMetric.containsKey(key): "$expected does not match $actual"

          // Some metric values are duration that will varies between runs
          // In the case, setting the expected value to "any" skips the assertion
          assert expectedValue == "any" || actualMetric[key] == expectedValue: "$expected does not match $actual"
        }
      }
    }

    // Recursively check that the children are the same on both side
    if (expected.children == null) {
      assert actual.children == null
    } else {
      expected.children.sort { it.node }
      actual.children.sort { it.node }

      [expected.children, actual.children].transpose().each { childPair ->
        assertSQLPlanEquals(childPair[0], childPair[1])
      }
    }
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

    def firstStagePlan = """
      {
        "node": "Exchange",
        "nodeId": -1909876497,
        "nodeDetailString": "hashpartitioning(string_col#0, 2)",
        "metrics": [
          {
            "data size total (min, med, max)": "any",
            "type": "size"
          }
        ],
        "children": [
          {
            "node": "WholeStageCodegen",
            "nodeId": 724251804,
            "metrics": [
              {
                "duration total (min, med, max)": "any",
                "type": "timing"
              }
            ],
            "children": [
              {
                "node": "HashAggregate",
                "nodeId": 1128016273,
                "nodeDetailString": "(keys=[string_col#0], functions=[partial_avg(double_col#1)])",
                "metrics": [
                  {
                    "aggregate time total (min, med, max)": "any",
                    "type": "timing"
                  },
                  {
                    "number of output rows": "any",
                    "type": "sum"
                  },
                  {
                    "peak memory total (min, med, max)": "any",
                    "type": "size"
                  }
                ],
                "children": [
                  {
                    "node": "InputAdapter",
                    "nodeId": 180293,
                    "children": [
                      {
                        "node": "LocalTableScan",
                        "nodeId": 1632930767,
                        "nodeDetailString": "[string_col#0, double_col#1]",
                        "metrics": [
                          {
                            "number of output rows": 3,
                            "type": "sum"
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    """

    def secondStagePlan = """
      {
        "node": "WholeStageCodegen",
        "nodeId": 724251804,
        "metrics": [
          {
            "duration total (min, med, max)": "any",
            "type": "timing"
          }
        ],
        "children": [
          {
            "node": "HashAggregate",
            "nodeId": 126020943,
            "nodeDetailString": "(keys=[string_col#0], functions=[avg(double_col#1)])",
            "metrics": [
              {
                "aggregate time total (min, med, max)": "any",
                "type": "timing"
              },
              {
                "avg hash probe (min, med, max)": "any",
                "type": "average"
              },
              {
                "number of output rows": 2,
                "type": "sum"
              },
              {
                "peak memory total (min, med, max)": "any",
                "type": "size"
              }
            ],
            "children": [
              {
                "node": "InputAdapter",
                "nodeId": 180293
              }
            ]
          }
        ]
      }
    """

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
          assertStringSQLPlanEquals(secondStagePlan, span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[0]"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assertStringSQLPlanEquals(firstStagePlan, span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
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

    def firstStagePlan = """
      {
        "node": "Exchange",
        "nodeId": "any",
        "nodeDetailString": "hashpartitioning(string_col#25, 2)",
        "metrics": [
          {
            "data size total (min, med, max)": "any",
            "type": "size"
          }
        ],
        "children": [
          {
            "node": "LocalTableScan",
            "nodeId": "any",
            "nodeDetailString": "[string_col#25]", 
            "metrics": [
              {
                "number of output rows": "any",
                "type": "sum"
              }
            ]
          }
        ]
      }
    """
    def secondStagePlan = """
      {
        "node": "Exchange",
        "nodeId": "any",
        "nodeDetailString": "hashpartitioning(string_col#21, 2)",
        "metrics": [
          {
            "data size total (min, med, max)": "any",
            "type": "size"
          }
        ],
        "children": [
          {
            "node": "LocalTableScan",
            "nodeId": "any",
            "nodeDetailString": "[string_col#21]", 
            "metrics": [
              {
                "number of output rows": "any",
                "type": "sum"
              }
            ]
          }
        ]
      }
    """
    def thirdStagePlan = """
      {
        "node": "Exchange",
        "nodeId": -1350402171,
        "nodeDetailString": "SinglePartition",
        "metrics": [
          {
            "data size total (min, med, max)": "any",
            "type": "size"
          }
        ],
        "children": [
          {
            "node": "WholeStageCodegen",
            "nodeId": 724251804,
            "metrics": [
              {
                "duration total (min, med, max)": "any",
                "type": "timing"
              }
            ],
            "children": [
              {
                "node": "HashAggregate",
                "nodeId": -879128980,
                "nodeDetailString": "(keys=[], functions=[partial_count(1)])",
                "metrics": [
                  {
                    "aggregate time total (min, med, max)": "any",
                    "type": "timing"
                  },
                  {
                    "number of output rows": "any",
                    "type": "sum"
                  }
                ],
                "children": [
                  {
                    "node": "Project",
                    "nodeId": 1355342585,
                    "children": [
                      {
                        "node": "SortMergeJoin",
                        "nodeId": -1975876610,
                        "nodeDetailString": "[string_col#21], [string_col#25], Inner",
                        "metrics": [
                          {
                            "number of output rows": "any",
                            "type": "sum"
                          }
                        ],
                        "children": [
                          {
                            "node": "InputAdapter",
                            "nodeId": 180293,
                            "children": [
                              {
                                "node": "WholeStageCodegen",
                                "nodeId": 724251804,
                                "metrics": [
                                  {
                                    "duration total (min, med, max)": "any",
                                    "type": "timing"
                                  }
                                ],
                                "children": [
                                  {
                                    "node": "Sort",
                                    "nodeId": 66807398,
                                    "nodeDetailString": "[string_col#21 ASC NULLS FIRST], false, 0",
                                    "metrics": [
                                      {
                                        "peak memory total (min, med, max)": "any",
                                        "type": "size"
                                      },
                                      {
                                        "sort time total (min, med, max)": "any",
                                        "type": "timing"
                                      }
                                    ],
                                    "children": [
                                      {
                                        "node": "InputAdapter",
                                        "nodeId": 180293
                                      }
                                    ]
                                  }
                                ]
                              }
                            ]
                          },
                          {
                            "node": "InputAdapter",
                            "nodeId": 180293,
                            "children": [
                              {
                                "node": "WholeStageCodegen",
                                "nodeId": 724251804,
                                "metrics": [
                                  {
                                    "duration total (min, med, max)": "any",
                                    "type": "timing"
                                  }
                                ],
                                "children": [
                                  {
                                    "node": "Sort",
                                    "nodeId": -952138782,
                                    "nodeDetailString": "[string_col#25 ASC NULLS FIRST], false, 0",
                                    "metrics": [
                                      {
                                        "peak memory total (min, med, max)": "any",
                                        "type": "size"
                                      }
                                    ],
                                    "children": [
                                      {
                                        "node": "InputAdapter",
                                        "nodeId": 180293
                                      }
                                    ]
                                  }
                                ]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    """
    def fourthStagePlan = """
      {
        "node": "WholeStageCodegen",
        "nodeId": 724251804,
        "metrics": [
          {
            "duration total (min, med, max)": "any",
            "type": "timing"
          }
        ],
        "children": [
          {
            "node": "HashAggregate",
            "nodeId": 724815342,
            "nodeDetailString": "(keys=[], functions=[count(1)])",
            "metrics": [
              {
                "number of output rows": 1,
                "type": "sum"
              }
            ],
            "children": [
              {
                "node": "InputAdapter",
                "nodeId": 180293
              }
            ]
          }
        ]
      }
    """

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
          assertStringSQLPlanSubset(fourthStagePlan, span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[2]"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assertStringSQLPlanEquals(thirdStagePlan, span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
          assert ["[0, 1]", "[1, 0]"].contains(span.tags["_dd.spark.sql_parent_stage_ids"])
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assertStringSQLPlanIn([firstStagePlan, secondStagePlan], span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assertStringSQLPlanIn([firstStagePlan, secondStagePlan], span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
        }
      }
    }
  }
}
