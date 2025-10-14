package datadog.trace.instrumentation.spark

import datadog.trace.agent.test.InstrumentationSpecification
import groovy.json.JsonSlurper
import org.apache.spark.sql.SparkSession

abstract class AbstractSpark32SqlTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.spark.enabled", "true")
    injectSysConfig("dd.integration.spark-openlineage.enabled", "true")
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

    def firstStagePlan = """
      {
        "node": "Exchange",
        "nodeId": "nodeId_4",
        "nodeDetailString": "hashpartitioning(string_col#0, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d38]",
        "meta": "any",
        "metrics": [
          {
            "data size": "any",
            "type": "size"
          },
          {
            "shuffle bytes written": "any",
            "type": "size"
          },
          {
            "shuffle records written": 3,
            "type": "sum"
          },
          {
            "shuffle write time": "any",
            "type": "nsTiming"
          }
        ],
        "children": [
          {
            "node": "WholeStageCodegen (1)",
            "nodeId": "nodeId_1",
            "meta": "any",
            "metrics": [
              {
                "duration": "any",
                "type": "timing"
              }
            ],
            "children": [
              {
                "node": "HashAggregate",
                "nodeId": "nodeId_3",
                "nodeDetailString": "(keys=[string_col#0], functions=[partial_avg(double_col#1)])",
                "meta": "any",
                "metrics": [
                  {
                    "number of output rows": 3,
                    "type": "sum"
                  },
                  {
                    "peak memory": "any",
                    "type": "size"
                  },
                  {
                    "time in aggregation build": "any",
                    "type": "timing"
                  }
                ],
                "children": [
                  {
                    "node": "LocalTableScan",
                    "nodeId": "nodeId_2",
                    "nodeDetailString": "[string_col#0, double_col#1]",
                    "meta": "any",
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
    """
    def secondStagePlan = """
      {
        "node": "WholeStageCodegen (2)",
        "nodeId": "nodeId_8",
        "meta": "any",
        "metrics": [
          {
            "duration": "any",
            "type": "timing"
          }
        ],
        "children": [
          {
            "node": "HashAggregate",
            "nodeId": "nodeId_9",
            "nodeDetailString": "(keys\\u003d[string_col#0], functions\\u003d[avg(double_col#1)])",
            "meta": "any",
            "metrics": [
              {
                "avg hash probe bucket list iters": "any",
                "type": "average"
              },
              {
                "number of output rows": 2,
                "type": "sum"
              },
              {
                "peak memory": "any",
                "type": "size"
              },
              {
                "time in aggregation build": "any",
                "type": "timing"
              }
            ],
            "children": [
              {
                "node": "InputAdapter",
                "nodeId": "nodeId_7",
                "meta": "any",
                "children": [
                  {
                    "node": "AQEShuffleRead",
                    "nodeId": "nodeId_5",
                    "nodeDetailString": "coalesced",
                    "meta": "any",
                    "metrics": [],
                    "children": [
                      {
                        "node": "ShuffleQueryStage",
                        "nodeId": "nodeId_6",
                        "nodeDetailString": "0",
                        "meta": "any",
                        "children": [
                          {
                            "node": "Exchange",
                            "nodeId": "nodeId_4",
                            "nodeDetailString": "hashpartitioning(string_col#0, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d38]",
                            "meta": "any",
                            "metrics": [
                              {
                                "data size": "any",
                                "type": "size"
                              },
                              {
                                "fetch wait time": "any",
                                "type": "timing"
                              },
                              {
                                "local blocks read": "any",
                                "type": "sum"
                              },
                              {
                                "local bytes read": "any",
                                "type": "size"
                              },
                              {
                                "records read": 3,
                                "type": "sum"
                              },
                              {
                                "shuffle bytes written": "any",
                                "type": "size"
                              },
                              {
                                "shuffle records written": 3,
                                "type": "sum"
                              },
                              {
                                "shuffle write time": "any",
                                "type": "nsTiming"
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
    def thirdStagePlan = """
      {
        "node": "Exchange",
        "nodeId": "nodeId_10",
        "nodeDetailString": "rangepartitioning(avg(double_col)#5 DESC NULLS LAST, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d67]",
        "meta": "any",
        "metrics": [
          {
            "data size": "any",
            "type": "size"
          },
          {
            "shuffle bytes written": "any",
            "type": "size"
          },
          {
            "shuffle records written": 2,
            "type": "sum"
          },
          {
            "shuffle write time": "any",
            "type": "nsTiming"
          }
        ],
        "children": [
          {
            "node": "WholeStageCodegen (2)",
            "nodeId": "nodeId_8",
            "meta": "any",
            "metrics": [
              {
                "duration": "any",
                "type": "timing"
              }
            ],
            "children": [
              {
                "node": "HashAggregate",
                "nodeId": "nodeId_9",
                "nodeDetailString": "(keys\\u003d[string_col#0], functions\\u003d[avg(double_col#1)])",
                "meta": "any",
                "metrics": [
                  {
                    "avg hash probe bucket list iters": "any",
                    "type": "average"
                  },
                  {
                    "number of output rows": "any",
                    "type": "sum"
                  },
                  {
                    "peak memory": "any",
                    "type": "size"
                  },
                  {
                    "time in aggregation build": "any",
                    "type": "timing"
                  }
                ],
                "children": [
                  {
                    "node": "InputAdapter",
                    "nodeId": "nodeId_7",
                    "meta": "any",
                    "children": [
                      {
                        "node": "AQEShuffleRead",
                        "nodeId": "nodeId_5",
                        "nodeDetailString": "coalesced",
                        "meta": "any",
                        "metrics": [],
                        "children": [
                          {
                            "node": "ShuffleQueryStage",
                            "nodeId": "nodeId_6",
                            "nodeDetailString": "0",
                            "meta": "any",
                            "children": [
                              {
                                "node": "Exchange",
                                "nodeId": "nodeId_4",
                                "nodeDetailString": "hashpartitioning(string_col#0, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d38]",
                                "meta": "any", 
                                "metrics": [
                                  {
                                    "data size": "any",
                                    "type": "size"
                                  },
                                  {
                                    "fetch wait time": "any",
                                    "type": "timing"
                                  },
                                  {
                                    "local blocks read": "any",
                                    "type": "sum"
                                  },
                                  {
                                    "local bytes read": "any",
                                    "type": "size"
                                  },
                                  {
                                    "records read": "any",
                                    "type": "sum"
                                  },
                                  {
                                    "shuffle bytes written": "any",
                                    "type": "size"
                                  },
                                  {
                                    "shuffle records written": "any",
                                    "type": "sum"
                                  },
                                  {
                                    "shuffle write time": "any",
                                    "type": "nsTiming"
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
        "node": "WholeStageCodegen (3)",
        "nodeId": "nodeId_12",
        "meta": "any",
        "metrics": [
          {
            "duration": "any",
            "type": "timing"
          }
        ],
        "children": [
          {
            "node": "Project",
            "nodeId": "nodeId_11",
            "nodeDetailString": "[string_col#0, cast(avg(double_col)#5 as string) AS avg(double_col)#12]",
            "meta": "any",
            "children": [
              {
                "node": "Sort",
                "nodeId": "nodeId_13",
                "nodeDetailString": "[avg(double_col)#5 DESC NULLS LAST], true, 0",
                "meta": "any",
                "metrics": [
                  {
                    "peak memory": "any",
                    "type": "size"
                  },
                  {
                    "sort time": "any",
                    "type": "timing"
                  },
                  {
                    "spill size": "any",
                    "type": "size"
                  }
                ],
                "children": [
                  {
                    "node": "InputAdapter",
                    "nodeId": "nodeId_7",
                    "meta": "any",
                    "children": [
                      {
                        "node": "AQEShuffleRead",
                        "nodeId": "nodeId_5",
                        "nodeDetailString": "coalesced",
                        "meta": "any",
                        "metrics": [],
                        "children": [
                          {
                            "node": "ShuffleQueryStage",
                            "nodeId": "nodeId_14",
                            "nodeDetailString": "1",
                            "meta": "any",
                            "children": [
                              {
                                "node": "Exchange",
                                "nodeId": "nodeId_10",
                                "nodeDetailString": "rangepartitioning(avg(double_col)#5 DESC NULLS LAST, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d67]",
                                "meta": "any",
                                "metrics": [
                                  {
                                    "data size": "any",
                                    "type": "size"
                                  },
                                  {
                                    "fetch wait time": "any",
                                    "type": "timing"
                                  },
                                  {
                                    "local blocks read": "any",
                                    "type": "sum"
                                  },
                                  {
                                    "local bytes read": "any",
                                    "type": "size"
                                  },
                                  {
                                    "records read": 2,
                                    "type": "sum"
                                  },
                                  {
                                    "shuffle bytes written": "any",
                                    "type": "size"
                                  },
                                  {
                                    "shuffle records written": 2,
                                    "type": "sum"
                                  },
                                  {
                                    "shuffle write time": "any",
                                    "type": "nsTiming"
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

    expect:
    def actualPlans = [] as List<String>
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
          actualPlans.add(span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
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
          actualPlans.add(span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
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
          actualPlans.add(span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
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
          actualPlans.add(span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
        }
      }
    }

    assertStringSQLPlanEqualsWithConsistentNodeIds(
      [firstStagePlan, secondStagePlan, thirdStagePlan, fourthStagePlan],
      [actualPlans[3], actualPlans[2], actualPlans[1], actualPlans[0]]
      )
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

    def firstStagePlan = """
      {
        "node": "Exchange",
        "nodeId": "any",
        "nodeDetailString": "hashpartitioning(string_col#28, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d119]",
        "meta": "any",
        "metrics": [
          {
            "data size": "any",
            "type": "size"
          },
          {
            "shuffle bytes written": "any",
            "type": "size"
          },
          {
            "shuffle records written": "any",
            "type": "sum"
          },
          {
            "shuffle write time": "any",
            "type": "nsTiming"
          }
        ],
        "children": [
          {
            "node": "LocalTableScan",
            "nodeId": "any",
            "nodeDetailString": "[string_col#28]",
            "meta": "any",
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
        "nodeDetailString": "hashpartitioning(string_col#32, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d120]",
        "meta": "any",
        "metrics": [
          {
            "data size": "any",
            "type": "size"
          },
          {
            "shuffle bytes written": "any",
            "type": "size"
          },
          {
            "shuffle records written": "any",
            "type": "sum"
          },
          {
            "shuffle write time": "any",
            "type": "nsTiming"
          }
        ],
        "children": [
          {
            "node": "LocalTableScan",
            "nodeId": "any",
            "nodeDetailString": "[string_col#32]",
            "meta": "any",
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
        "nodeId": "nodeId_7",
        "nodeDetailString": "SinglePartition, ENSURE_REQUIREMENTS, [plan_id\\u003d230]",
        "meta": "any",
        "metrics": [
          {
            "data size": "any",
            "type": "size"
          },
          {
            "shuffle bytes written": "any",
            "type": "size"
          },
          {
            "shuffle records written": 1,
            "type": "sum"
          },
          {
            "shuffle write time": "any",
            "type": "nsTiming"
          }
        ],
        "children": [
          {
            "node": "WholeStageCodegen (3)",
            "nodeId": "nodeId_9",
            "meta": "any",
            "metrics": [
              {
                "duration": "any",
                "type": "timing"
              }
            ],
            "children": [
              {
                "node": "HashAggregate",
                "nodeId": "nodeId_16",
                "nodeDetailString": "(keys\\u003d[], functions\\u003d[partial_count(1)])",
                "meta": "any",
                "metrics": [
                  {
                    "number of output rows": 1,
                    "type": "sum"
                  },
                  {
                    "time in aggregation build": "any",
                    "type": "timing"
                  }
                ],
                "children": [
                  {
                    "node": "Project",
                    "nodeId": "nodeId_13",
                    "meta": "any",
                    "children": [
                      {
                        "node": "SortMergeJoin",
                        "nodeId": "nodeId_15",
                        "nodeDetailString": "[string_col#28], [string_col#32], Inner",
                        "meta": "any",
                        "metrics": [
                          {
                            "number of output rows": "any",
                            "type": "sum"
                          }
                        ],
                        "children": [
                          {
                            "node": "InputAdapter",
                            "nodeId": "nodeId_6",
                            "meta": "any",
                            "children": [
                              {
                                "node": "WholeStageCodegen (1)",
                                "nodeId": "nodeId_8",
                                "meta": "any",
                                "metrics": [
                                  {
                                    "duration": "any",
                                    "type": "timing"
                                  }
                                ],
                                "children": [
                                  {
                                    "node": "Sort",
                                    "nodeId": "nodeId_11",
                                    "nodeDetailString": "[string_col#28 ASC NULLS FIRST], false, 0",
                                    "meta": "any",
                                    "metrics": [
                                      {
                                        "peak memory": "any",
                                        "type": "size"
                                      },
                                      {
                                        "sort time": "any",
                                        "type": "timing"
                                      },
                                      {
                                        "spill size": "any",
                                        "type": "size"
                                      }
                                    ],
                                    "children": [
                                      {
                                        "node": "InputAdapter",
                                        "nodeId": "nodeId_6",
                                        "meta": "any",
                                        "children": [
                                          {
                                            "node": "AQEShuffleRead",
                                            "nodeId": "nodeId_5",
                                            "nodeDetailString": "coalesced",
                                            "meta": "any",
                                            "metrics": [],
                                            "children": [
                                              {
                                                "node": "ShuffleQueryStage",
                                                "nodeId": "nodeId_14",
                                                "nodeDetailString": "0",
                                                "meta": "any",
                                                "children": [
                                                  {
                                                    "node": "Exchange",
                                                    "nodeId": "nodeId_2",
                                                    "nodeDetailString": "hashpartitioning(string_col#28, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d119]",
                                                    "meta": "any",
                                                    "metrics": [
                                                      {
                                                        "data size": "any",
                                                        "type": "size"
                                                      },
                                                      {
                                                        "fetch wait time": "any",
                                                        "type": "timing"
                                                      },
                                                      {
                                                        "local blocks read": "any",
                                                        "type": "sum"
                                                      },
                                                      {
                                                        "local bytes read": "any",
                                                        "type": "size"
                                                      },
                                                      {
                                                        "records read": "any",
                                                        "type": "sum"
                                                      },
                                                      {
                                                        "shuffle bytes written": "any",
                                                        "type": "size"
                                                      },
                                                      {
                                                        "shuffle records written": "any",
                                                        "type": "sum"
                                                      },
                                                      {
                                                        "shuffle write time": "any",
                                                        "type": "nsTiming"
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
                          },
                          {
                            "node": "InputAdapter",
                            "nodeId": "nodeId_6",
                            "meta": "any",
                            "children": [
                              {
                                "node": "WholeStageCodegen (2)",
                                "nodeId": "nodeId_12",
                                "meta": "any",
                                "metrics": [
                                  {
                                    "duration": "any",
                                    "type": "timing"
                                  }
                                ],
                                "children": [
                                  {
                                    "node": "Sort",
                                    "nodeId": "nodeId_17",
                                    "nodeDetailString": "[string_col#32 ASC NULLS FIRST], false, 0",
                                    "meta": "any",
                                    "metrics": [
                                      {
                                        "peak memory": "any",
                                        "type": "size"
                                      },
                                      {
                                        "sort time": "any",
                                        "type": "timing"
                                      },
                                      {
                                        "spill size": "any",
                                        "type": "size"
                                      }
                                    ],
                                    "children": [
                                      {
                                        "node": "InputAdapter",
                                        "nodeId": "nodeId_6",
                                        "meta": "any",
                                        "children": [
                                          {
                                            "node": "AQEShuffleRead",
                                            "nodeId": "nodeId_5",
                                            "nodeDetailString": "coalesced",
                                            "meta": "any",
                                            "metrics": [],
                                            "children": [
                                              {
                                                "node": "ShuffleQueryStage",
                                                "nodeId": "nodeId_10",
                                                "nodeDetailString": "1",
                                                "meta": "any",
                                                "children": [
                                                  {
                                                    "node": "Exchange",
                                                    "nodeId": "nodeId_4",
                                                    "nodeDetailString": "hashpartitioning(string_col#32, 2), ENSURE_REQUIREMENTS, [plan_id\\u003d120]",
                                                    "meta": "any",
                                                    "metrics": [
                                                      {
                                                        "data size": "any",
                                                        "type": "size"
                                                      },
                                                      {
                                                        "fetch wait time": "any",
                                                        "type": "timing"
                                                      },
                                                      {
                                                        "local blocks read": "any",
                                                        "type": "sum"
                                                      },
                                                      {
                                                        "local bytes read": "any",
                                                        "type": "size"
                                                      },
                                                      {
                                                        "records read": "any",
                                                        "type": "sum"
                                                      },
                                                      {
                                                        "shuffle bytes written": "any",
                                                        "type": "size"
                                                      },
                                                      {
                                                        "shuffle records written": "any",
                                                        "type": "sum"
                                                      },
                                                      {
                                                        "shuffle write time": "any",
                                                        "type": "nsTiming"
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
        "node": "WholeStageCodegen (4)",
        "nodeId": "nodeId_20",
        "meta": "any",
        "metrics": [
          {
            "duration": "any",
            "type": "timing"
          }
        ],
        "children": [
          {
            "node": "HashAggregate",
            "nodeId": "nodeId_18",
            "nodeDetailString": "(keys\\u003d[], functions\\u003d[count(1)])",
            "meta": "any",
            "metrics": [
              {
                "number of output rows": "any",
                "type": "sum"
              },
              {
                "time in aggregation build": "any",
                "type": "timing"
              }
            ],
            "children": [
              {
                "node": "InputAdapter",
                "nodeId": "nodeId_6",
                "meta": "any",
                "children": [
                  {
                    "node": "ShuffleQueryStage",
                    "nodeId": "nodeId_19",
                    "nodeDetailString": "2",
                    "meta": "any",
                    "children": [
                      {
                        "node": "Exchange",
                        "nodeId": "nodeId_7",
                        "nodeDetailString": "SinglePartition, ENSURE_REQUIREMENTS, [plan_id\\u003d230]",
                        "meta": "any",
                        "metrics": [
                          {
                            "data size": "any",
                            "type": "size"
                          },
                          {
                            "fetch wait time": "any",
                            "type": "timing"
                          },
                          {
                            "local blocks read": "any",
                            "type": "sum"
                          },
                          {
                            "local bytes read": "any",
                            "type": "size"
                          },
                          {
                            "records read": "any",
                            "type": "sum"
                          },
                          {
                            "shuffle bytes written": "any",
                            "type": "size"
                          },
                          {
                            "shuffle records written": "any",
                            "type": "sum"
                          },
                          {
                            "shuffle write time": "any",
                            "type": "nsTiming"
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

    expect:
    def actualPlans = [] as List<String>
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
          actualPlans.add(span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
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
          actualPlans.add(span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
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
          actualPlans.add(span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
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
          actualPlans.add(span.tags["_dd.spark.sql_plan"].toString())
          assert span.tags["_dd.spark.physical_plan"] == null
          assert span.tags["_dd.spark.sql_parent_stage_ids"] == "[]"
        }
      }
    }
    assertStringSQLPlanEqualsWithConsistentNodeIds(
      [firstStagePlan, secondStagePlan, thirdStagePlan, fourthStagePlan],
      [actualPlans[3], actualPlans[2], actualPlans[1], actualPlans[0]]
      )
  }

  static assertStringSQLPlanEqualsWithConsistentNodeIds(List<String> expectedPlans, List<String> actualPlans) {
    def jsonSlurper = new JsonSlurper()

    def actualToNormalized = [:]
    def nodeIdCounter = 1

    expectedPlans.eachWithIndex {
      it, i -> {
        def expectedParsed = jsonSlurper.parseText(it)
        def actualParsed = jsonSlurper.parseText(actualPlans[i])

        // Extract all nodeIds from actual plans and create mapping
        extractNodeIds(actualParsed).each { nodeId ->
          if (nodeId != null && nodeId != "any" && !actualToNormalized.containsKey(nodeId)) {
            actualToNormalized[nodeId] = "nodeId_${nodeIdCounter++}"
          }
        }

        def normalizedActual = normalizeNodeIds(actualParsed, actualToNormalized)
        AbstractSpark24SqlTest.assertSQLPlanEquals(expectedParsed, normalizedActual)
      }
    }
  }

  private static Set<Object> extractNodeIds(Object plan) {
    Set<Object> nodeIds = new HashSet<>()
    if (plan instanceof Map) {
      if (plan.containsKey("nodeId")) {
        nodeIds.add(plan.nodeId)
      }

      if (plan.containsKey("children") && plan.children != null) {
        def sorted = plan.children.sort { it.node }
        sorted.each { child ->
          nodeIds.addAll(extractNodeIds(child))
        }
      }
    }

    return nodeIds
  }

  private static Object normalizeNodeIds(Object plan, Map<Object, String> nodeIdMapping) {
    if (plan instanceof Map) {
      def nodeId = plan["nodeId"]
      def children = plan["children"]
      if (nodeId != null)  {
        plan["nodeId"] = nodeIdMapping[nodeId]
      }
      if (children != null) {
        plan["children"] = children.collect { normalizeNodeIds(it, nodeIdMapping) }
      }

      plan.each { key, value ->
        if (key != "nodeId" && key != "children") {
          plan[key] = value
        }
      }
      return plan
    }
    return plan
  }
}
