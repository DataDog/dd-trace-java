[ {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_trace_id},
    "span_id" : ${content_span_id},
    "parent_id" : ${content_parent_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.step",
    "resource" : "* print 'first'",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 0,
    "metrics" : {
      "step.endLine" : 7,
      "step.startLine" : 7
    },
    "meta" : {
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "step.name" : "* print 'first'",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_trace_id_2},
    "span_id" : ${content_span_id_2},
    "parent_id" : ${content_parent_id_2},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.step",
    "resource" : "* print 'second'",
    "start" : ${content_start_2},
    "duration" : ${content_duration_2},
    "error" : 0,
    "metrics" : {
      "step.endLine" : 10,
      "step.startLine" : 10
    },
    "meta" : {
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "step.name" : "* print 'second'",
      "env" : "none"
    }
  }
}, {
  "type" : "test_suite_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test_suite",
    "resource" : "[org/example/test_unskippable] test unskippable",
    "start" : ${content_start_3},
    "duration" : ${content_duration_3},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "test.type" : "test",
      "test.module" : "karate",
      "test.status" : "pass",
      "test.traits" : "{\"category\":[\"foo\"]}",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "span.kind" : "test_suite_end",
      "test.suite" : "[org/example/test_unskippable] test unskippable",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate"
    }
  }
}, {
  "type" : "test",
  "version" : 2,
  "content" : {
    "trace_id" : ${content_trace_id},
    "span_id" : ${content_parent_id},
    "parent_id" : ${content_parent_id_3},
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "itr_correlation_id" : "itrCorrelationId",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test",
    "resource" : "[org/example/test_unskippable] test unskippable.first scenario",
    "start" : ${content_start_4},
    "duration" : ${content_duration_4},
    "error" : 0,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.module" : "karate",
      "test.status" : "pass",
      "language" : "jvm",
      "test.itr.forced_run" : "true",
      "library_version" : ${content_meta_library_version},
      "test.itr.unskippable" : "true",
      "test.name" : "first scenario",
      "span.kind" : "test",
      "test.suite" : "[org/example/test_unskippable] test unskippable",
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "test.traits" : "{\"category\":[\"bar\",\"datadog_itr_unskippable\",\"foo\"]}",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "component" : "karate",
      "_dd.profiling.ctx" : "test",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate"
    }
  }
}, {
  "type" : "test",
  "version" : 2,
  "content" : {
    "trace_id" : ${content_trace_id_2},
    "span_id" : ${content_parent_id_2},
    "parent_id" : ${content_parent_id_3},
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "itr_correlation_id" : "itrCorrelationId",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test",
    "resource" : "[org/example/test_unskippable] test unskippable.second scenario",
    "start" : ${content_start_5},
    "duration" : ${content_duration_5},
    "error" : 0,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.module" : "karate",
      "test.status" : "pass",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "test.name" : "second scenario",
      "span.kind" : "test",
      "test.suite" : "[org/example/test_unskippable] test unskippable",
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "test.traits" : "{\"category\":[\"foo\"]}",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "component" : "karate",
      "_dd.profiling.ctx" : "test",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate"
    }
  }
}, {
  "type" : "test_session_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test_session",
    "resource" : "karate",
    "start" : ${content_start_6},
    "duration" : ${content_duration_6},
    "error" : 0,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "test.itr.tests_skipping.count" : 0,
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "test.type" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.status" : "pass",
      "test_session.name" : "session-name",
      "language" : "jvm",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "_dd.profiling.ctx" : "test",
      "span.kind" : "test_session_end",
      "test.itr.tests_skipping.type" : "test",
      "runtime-id" : ${content_meta_runtime_id},
      "test.command" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate",
      "test.itr.tests_skipping.enabled" : "true"
    }
  }
}, {
  "type" : "test_module_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test_module",
    "resource" : "karate",
    "start" : ${content_start_7},
    "duration" : ${content_duration_7},
    "error" : 0,
    "metrics" : {
      "test.itr.tests_skipping.count" : 0
    },
    "meta" : {
      "test.type" : "test",
      "test.module" : "karate",
      "test.status" : "pass",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "span.kind" : "test_module_end",
      "test.itr.tests_skipping.type" : "test",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate",
      "test.itr.tests_skipping.enabled" : "true"
    }
  }
} ]