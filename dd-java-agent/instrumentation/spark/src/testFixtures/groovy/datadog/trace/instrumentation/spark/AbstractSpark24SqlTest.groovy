package datadog.trace.instrumentation.spark

import com.fasterxml.jackson.databind.ObjectMapper
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

  static assertStringSQLPlanIn(ArrayList<String> expectedStrings, String actualString, String name) {
    def jsonSlurper = new JsonSlurper()
    def actual = jsonSlurper.parseText(actualString)

    for (String expectedString : expectedStrings) {
      try {
        def expected = jsonSlurper.parseText(expectedString)
        assertSQLPlanEquals(expected, actual, name)
        return
      } catch (AssertionError e) {
        System.err.println("Failed to assert $expectedString, attempting next")
      }
    }

    throw new AssertionError("No matching SQL Plan found for $actualString in $expectedStrings")
  }

  static assertStringSQLPlanEquals(String expectedString, String actualString, String name) {
    def jsonSlurper = new JsonSlurper()

    def expected = jsonSlurper.parseText(expectedString)
    def actual = jsonSlurper.parseText(actualString)

    assertSQLPlanEquals(expected, actual, name)
  }

  // Similar to assertStringSQLPlanEquals, but the actual plan can be a subset of the expected plan
  // This is used for spark 2.4 where the exact SQL plan is not deterministic
  protected static assertStringSQLPlanSubset(String expectedString, String actualString, String name) {
    def jsonSlurper = new JsonSlurper()

    def expected = jsonSlurper.parseText(expectedString)
    def actual = jsonSlurper.parseText(actualString)

    try {
      assertSQLPlanEquals(expected.children[0], actual, name)
      return // If is a subset, the test is successful
    }
    catch (AssertionError e) {
      System.err.println("Failed to assert $expectedString, attempting parent")
    }

    assertSQLPlanEquals(expected, actual, name)
  }

  private static assertSQLPlanEquals(Object expected, Object actual, String name) {
    assert expected.node == actual.node
    assert expected.keySet() == actual.keySet()

    def prefix = "$name on $expected.node node:\n\t"

    // Checking all keys except children, meta, and metrics that are checked after
    expected.keySet().each { key ->
      if (!['children', 'metrics', 'meta'].contains(key)) {
        if (expected[key] != "any") {
          // Some metric values will varies between runs
          // In the case, setting the expected value to "any" skips the assertion
          assert expected[key] == actual[key]: prefix + "value of \"$key\" does not match $expected.key, got $actual.key"
        }
      }
    }

    // Checking the meta values are the same on both sides
    if (expected.meta == null) {
      assert actual.meta == null
    } else {
      try {
        def expectedMeta = expected.meta
        def actualMeta = actual.meta

        assert actualMeta.size() == expectedMeta.size() : prefix + "meta size of $expectedMeta does not match $actualMeta"

        def actualUnknown = [] // List of values for all valid unknown keys
        actual.meta.each { actualMetaKey, actualMetaValue ->
          if (!expectedMeta.containsKey(actualMetaKey) && actualMetaKey.startsWith("_dd.unknown_key.")) {
            actualUnknown.add(parseNestedMetaObject(actualMetaValue))
          } else if (!expectedMeta.containsKey(actualMetaKey)) {
            throw new AssertionError(prefix + "unexpected key \"$actualMetaKey\" found, not valid unknown key with prefix '_dd.unknown_key.' or in $expectedMeta")
          }
        }

        expected.meta.each { expectedMetaKey, expectedMetaValue ->
          if (actualMeta.containsKey(expectedMetaKey)) {
            def actualMetaValue = actualMeta[expectedMetaKey]
            if (expectedMetaValue instanceof List) {
              assert expectedMetaValue ==~ actualMetaValue : prefix + "value of meta key \"$expectedMetaKey\" does not match \"$expectedMetaValue\", got \"$actualMetaValue\""
            } else {
              // Don't assert meta values where expectation is "any"
              assert expectedMetaValue == "any" || expectedMetaValue == actualMetaValue: prefix + "value of meta key \"$expectedMetaKey\" does not match \"$expectedMetaValue\", got \"$actualMetaValue\""
            }
          } else if (actualUnknown.size() > 0) {
            // If expected key not found, attempt to match value against those from valid unknown keys
            def expectedMetaValueToCompare = parseNestedMetaObject(expectedMetaValue)
            assert actualUnknown.indexOf(expectedMetaValueToCompare) >= 0 : prefix + "meta key \"$expectedMetaKey\" not found in $actualMeta\n\tattempted to match against value \"$expectedMetaValueToCompare\" with unknown keys, but not found"
            actualUnknown.drop(actualUnknown.indexOf(expectedMetaValueToCompare))
          } else {
            // Defensive, should never happen
            assert actualMeta.containsKey(expectedMetaKey) : prefix + "meta key \"$expectedMetaKey\" not found in $actualMeta"
          }
        }
      } catch (AssertionError e) {
        generateMetaExpectations(actual, name)
        throw e
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

        assert expectedMetric.size() == actualMetric.size(): prefix + "metric size of $expectedMetric does not match $actualMetric"

        // Each metric is a dict { "metric_name": "metric_value", "type": "metric_type" }
        expectedMetric.each { key, expectedValue ->
          assert actualMetric.containsKey(key): prefix + "metric key \"$key\" not found in $actualMetric"

          // Some metric values are duration that will varies between runs
          // In the case, setting the expected value to "any" skips the assertion
          def actualValue = actualMetric[key]
          assert expectedValue == "any" || actualValue == expectedValue: prefix + "value of metric key \"$key\" does not match \"$expectedValue\", got $actualValue"
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
        assertSQLPlanEquals(childPair[0], childPair[1], name)
      }
    }
  }

  // Parse any nested objects to a standard form that ignores the keys
  // Right now we do this by just asserting the key set and none of the values
  static Object parseNestedMetaObject(Object value) {
    if (value instanceof Map) {
      return value.keySet()
    } else {
      return value
    }
  }

  static String escapeJsonString(String source) {
    for (char c : """{}"[]()""".toCharArray()) {
      source = source.replace(c.toString(), "\\" + c.toString())
    }
    return source
  }

  private static generateMetaExpectations(Object actual, String name) {
    ObjectMapper mapper = new ObjectMapper()

    def simpleString = actual["nodeDetailString"]
    def child = "N/A"
    def values = [:]

    actual["meta"].each { key, value ->
      if (key == "_dd.unparsed") {
        values.put("_dd.unparsed", "any")
        child = value
      } else {
        values.put(key, value)
      }
    }
    def prettyValues = "\n\"meta\": " + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(values.sort { it -> it.key }) + ","
    System.err.println("$actual.node\n\tname=$name\n\tchild=$child\n\tvalues=$prettyValues\n\tsimpleString=$simpleString")
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
        "meta": {
          "_dd.unknown_key.0" : {
            "HashPartitioning" : {
              "_dd.unknown_key.0" : [ "string_col#0" ],
              "_dd.unknown_key.1" : 2
            }
          },
          "_dd.unknown_key.2" : "none",
          "_dd.unparsed" : "any"
        },
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
            "meta": {"_dd.unparsed": "any"},
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
                "meta": {
                  "_dd.unknown_key.0" : "none",
                  "_dd.unknown_key.1" : [ "string_col#0" ],
                  "_dd.unknown_key.2" : [ "partial_avg(double_col#1)" ],
                  "_dd.unknown_key.3" : [ "sum#16", "count#17L" ],
                  "_dd.unknown_key.4" : 0,
                  "_dd.unknown_key.5" : [ "string_col#0", "sum#18", "count#19L" ],
                  "_dd.unparsed" : "any"
                },
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
                    "meta": {"_dd.unparsed": "any"},
                    "children": [
                      {
                        "node": "LocalTableScan",
                        "nodeId": 1632930767,
                        "nodeDetailString": "[string_col#0, double_col#1]",
                        "meta": {
                          "_dd.unknown_key.0" : [ "string_col#0", "double_col#1" ],
                          "_dd.unknown_key.1" : [ ]
                        },
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
        "meta": {"_dd.unparsed": "any"},
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
            "meta": {
              "_dd.unknown_key.0" : [ "string_col#0" ],
              "_dd.unknown_key.1" : [ "string_col#0" ],
              "_dd.unknown_key.2" : [ "avg(double_col#1)" ],
              "_dd.unknown_key.3" : [ "avg(double_col#1)#4" ],
              "_dd.unknown_key.4" : 1,
              "_dd.unknown_key.5" : [ "string_col#0", "avg(double_col#1)#4 AS avg(double_col)#5" ],
              "_dd.unparsed" : "any"
            },
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
                "nodeId": 180293,
                "meta": {"_dd.unparsed": "any"}
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
          assertStringSQLPlanEquals(secondStagePlan, span.tags["_dd.spark.sql_plan"].toString(), "secondStagePlan")
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[0]"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assertStringSQLPlanEquals(firstStagePlan, span.tags["_dd.spark.sql_plan"].toString(), "firstStagePlan")
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
        "meta": {
          "_dd.unknown_key.0" : {
            "HashPartitioning" : {
              "_dd.unknown_key.0" : [ "string_col#25" ],
              "_dd.unknown_key.1" : 2
            }
          },
          "_dd.unknown_key.2" : "none",
          "_dd.unparsed" : "any"
        },
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
            "meta": {
              "_dd.unknown_key.0" : [ "string_col#25" ],
              "_dd.unknown_key.1" : [ ]
            },
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
        "meta": {
          "_dd.unknown_key.0" : {
            "HashPartitioning" : {
              "_dd.unknown_key.0" : [ "string_col#21" ],
              "_dd.unknown_key.1" : 2
            }
          },
          "_dd.unknown_key.2" : "none",
          "_dd.unparsed" : "any"
        },
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
            "meta": {
              "_dd.unknown_key.0" : [ "string_col#21" ],
              "_dd.unknown_key.1" : [ ]
            },
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
        "meta": {
          "_dd.unknown_key.0" : "SinglePartition",
          "_dd.unknown_key.2" : "none",
          "_dd.unparsed" : "any"
        },
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
            "meta": {"_dd.unparsed": "any"},
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
                "meta": {
                  "_dd.unknown_key.0" : "none",
                  "_dd.unknown_key.1" : [ ],
                  "_dd.unknown_key.2" : [ "partial_count(1)" ],
                  "_dd.unknown_key.3" : [ "count#38L" ],
                  "_dd.unknown_key.4" : 0,
                  "_dd.unknown_key.5" : [ "count#39L" ],
                  "_dd.unparsed" : "any"
                },
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
                    "meta": {
                      "_dd.unknown_key.0" : [ ],
                      "_dd.unparsed" : "any"
                    },
                    "children": [
                      {
                        "node": "SortMergeJoin",
                        "nodeId": -1975876610,
                        "nodeDetailString": "[string_col#21], [string_col#25], Inner",
                        "meta": {
                          "_dd.unknown_key.0" : [ "string_col#21" ],
                          "_dd.unknown_key.1" : [ "string_col#25" ],
                          "_dd.unknown_key.2" : "Inner",
                          "_dd.unknown_key.3" : "none",
                          "_dd.unparsed" : "any"
                        },
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
                            "meta": {"_dd.unparsed": "any"},
                            "children": [
                              {
                                "node": "WholeStageCodegen",
                                "nodeId": 724251804,
                                "meta": {"_dd.unparsed": "any"},
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
                                    "meta": {
                                      "_dd.unknown_key.0" : [ "string_col#21 ASC NULLS FIRST" ],
                                      "_dd.unknown_key.1" : false,
                                      "_dd.unknown_key.3" : 0,
                                      "_dd.unparsed" : "any"
                                    },
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
                                        "nodeId": 180293,
                                        "meta": {"_dd.unparsed": "any"}
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
                            "meta": {"_dd.unparsed": "any"},
                            "children": [
                              {
                                "node": "WholeStageCodegen",
                                "nodeId": 724251804,
                                "meta": {"_dd.unparsed": "any"},
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
                                    "meta": {
                                      "_dd.unknown_key.0" : [ "string_col#25 ASC NULLS FIRST" ],
                                      "_dd.unknown_key.1" : false,
                                      "_dd.unknown_key.3" : 0,
                                      "_dd.unparsed" : "any"
                                    },
                                    "metrics": [
                                      {
                                        "peak memory total (min, med, max)": "any",
                                        "type": "size"
                                      }
                                    ],
                                    "children": [
                                      {
                                        "node": "InputAdapter",
                                        "nodeId": 180293,
                                        "meta": {"_dd.unparsed": "any"}
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
        "meta": {"_dd.unparsed": "any"},
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
            "meta": {
              "_dd.unknown_key.0" : [ ],
              "_dd.unknown_key.1" : [ ],
              "_dd.unknown_key.2" : [ "count(1)" ],
              "_dd.unknown_key.3" : [ "count(1)#35L" ],
              "_dd.unknown_key.4" : 0,
              "_dd.unknown_key.5" : [ "count(1)#35L AS count#36L" ],
              "_dd.unparsed" : "any"
            },
            "metrics": [
              {
                "number of output rows": 1,
                "type": "sum"
              }
            ],
            "children": [
              {
                "node": "InputAdapter",
                "nodeId": 180293,
                "meta": {"_dd.unparsed": "any"}
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
          assertStringSQLPlanSubset(fourthStagePlan, span.tags["_dd.spark.sql_plan"].toString(), "fourthStagePlan")
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[2]"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assertStringSQLPlanEquals(thirdStagePlan, span.tags["_dd.spark.sql_plan"].toString(), "thirdStagePlan")
          assert span.tags["_dd.spark.physical_plan"] == null
          assert ["[0, 1]", "[1, 0]"].contains(span.tags["_dd.spark.sql_parent_stage_ids"])
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assertStringSQLPlanIn([firstStagePlan, secondStagePlan], span.tags["_dd.spark.sql_plan"].toString(), "secondStagePlan")
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
        }
        span {
          operationName "spark.stage"
          spanType "spark"
          childOf(span(2))
          assertStringSQLPlanIn([firstStagePlan, secondStagePlan], span.tags["_dd.spark.sql_plan"].toString(), "firstStagePlan")
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
        }
      }
    }
  }
}
