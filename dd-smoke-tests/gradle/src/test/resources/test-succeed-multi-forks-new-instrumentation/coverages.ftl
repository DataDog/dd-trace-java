[ {
  "test_session_id" : ${content_test_session_id},
  "test_suite_id" : ${content_test_suite_id},
  "span_id" : ${content_span_id},
  "files" : [ {
    "filename" : "src/test/java/datadog/smoke/TestSucceed.java",
    "segments" : [ [ 7, -1, 7, -1, -1 ], [ 10, -1, 11, -1, -1 ] ]
  }, {
    "filename" : "src/main/java/datadog/smoke/Calculator.java",
    "segments" : [ [ 5, -1, 5, -1, -1 ] ]
  } ]
}, {
  "test_session_id" : ${content_test_session_id},
  "test_suite_id" : ${content_test_suite_id_2},
  "span_id" : ${content_span_id_2},
  "files" : [ {
    "filename" : "src/main/java/datadog/smoke/Calculator.java",
    "segments" : [ [ 8, -1, 8, -1, -1 ] ]
  }, {
    "filename" : "src/test/java/datadog/smoke/TestSucceedJunit5.java",
    "segments" : [ [ 10, -1, 11, -1, -1 ] ]
  } ]
} ]