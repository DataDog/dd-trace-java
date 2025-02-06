[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "component" : "karate",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "step.name" : "Given def p = 'b'"
    },
    "metrics" : {
      "step.endLine" : 6,
      "step.startLine" : 6
    },
    "name" : "karate.step",
    "parent_id" : ${content_parent_id},
    "resource" : "Given def p = 'b'",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id},
    "start" : ${content_start},
    "trace_id" : ${content_trace_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_2},
    "error" : 0,
    "meta" : {
      "component" : "karate",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "step.name" : "Then match response == value"
    },
    "metrics" : {
      "step.endLine" : 8,
      "step.startLine" : 8
    },
    "name" : "karate.step",
    "parent_id" : ${content_parent_id},
    "resource" : "Then match response == value",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_2},
    "start" : ${content_start_2},
    "trace_id" : ${content_trace_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_3},
    "error" : 0,
    "meta" : {
      "component" : "karate",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "step.name" : "When def response = p + p"
    },
    "metrics" : {
      "step.endLine" : 7,
      "step.startLine" : 7
    },
    "name" : "karate.step",
    "parent_id" : ${content_parent_id},
    "resource" : "When def response = p + p",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_3},
    "start" : ${content_start_3},
    "trace_id" : ${content_trace_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_4},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "component" : "karate",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_suite_end",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "karate",
      "test.status" : "pass",
      "test.suite" : "[org/example/test_parameterized] test parameterized",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count}
    },
    "name" : "karate.test_suite",
    "resource" : "[org/example/test_parameterized] test parameterized",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_4},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id}
  },
  "type" : "test_suite_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_5},
    "error" : 0,
    "itr_correlation_id" : "itrCorrelationId",
    "meta" : {
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "karate",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "karate",
      "test.name" : "first scenario as an outline",
      "test.parameters" : "{\"param\":\"'a'\",\"value\":\"aa\"}",
      "test.skip_reason" : "Skipped by Datadog Intelligent Test Runner",
      "test.skipped_by_itr" : "true",
      "test.status" : "skip",
      "test.suite" : "[org/example/test_parameterized] test parameterized",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "karate.test",
    "parent_id" : ${content_parent_id_2},
    "resource" : "[org/example/test_parameterized] test parameterized.first scenario as an outline",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_4},
    "start" : ${content_start_5},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id_2}
  },
  "type" : "test",
  "version" : 2
}, {
  "content" : {
    "duration" : ${content_duration_6},
    "error" : 0,
    "itr_correlation_id" : "itrCorrelationId",
    "meta" : {
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "karate",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "karate",
      "test.name" : "first scenario as an outline",
      "test.parameters" : "{\"param\":\"'b'\",\"value\":\"bb\"}",
      "test.status" : "pass",
      "test.suite" : "[org/example/test_parameterized] test parameterized",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "karate.test",
    "parent_id" : ${content_parent_id_2},
    "resource" : "[org/example/test_parameterized] test parameterized.first scenario as an outline",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_parent_id},
    "start" : ${content_start_6},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id}
  },
  "type" : "test",
  "version" : 2
}, {
  "content" : {
    "duration" : ${content_duration_7},
    "error" : 0,
    "meta" : {
      "_dd.ci.itr.tests_skipped" : "true",
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "karate",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test_session_end",
      "test.command" : "karate",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.itr.tests_skipping.enabled" : "true",
      "test.itr.tests_skipping.type" : "test",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_4},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.itr.tests_skipping.count" : 1
    },
    "name" : "karate.test_session",
    "resource" : "karate",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_7},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_8},
    "error" : 0,
    "meta" : {
      "_dd.ci.itr.tests_skipped" : "true",
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "component" : "karate",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.itr.tests_skipping.enabled" : "true",
      "test.itr.tests_skipping.type" : "test",
      "test.module" : "karate",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_5},
      "test.itr.tests_skipping.count" : 1
    },
    "name" : "karate.test_module",
    "resource" : "karate",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_8},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
} ]