[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "component" : "karate",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "step.name" : "* 1 == 1"
    },
    "metrics" : {
      "step.endLine" : 6,
      "step.startLine" : 6
    },
    "name" : "karate.step",
    "parent_id" : ${content_parent_id},
    "resource" : "* 1 == 1",
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
      "step.name" : "* abortSuiteOnFailure = true"
    },
    "metrics" : {
      "step.endLine" : 5,
      "step.startLine" : 5
    },
    "name" : "karate.step",
    "parent_id" : ${content_parent_id},
    "resource" : "* abortSuiteOnFailure = true",
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
    "error" : 1,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "component" : "karate",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "error.type" : "java.lang.RuntimeException",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_suite_end",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "karate-2.0",
      "test.status" : "fail",
      "test.suite" : "[org/example/test_abort_suite] test abort suite",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count}
    },
    "name" : "karate.test_suite",
    "resource" : "[org/example/test_abort_suite] test abort suite",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_3},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id}
  },
  "type" : "test_suite_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_4},
    "error" : 1,
    "meta" : {
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "karate",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack_2},
      "error.type" : "java.lang.RuntimeException",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.failure_suppressed" : "true",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "karate-2.0",
      "test.name" : "aborting scenario",
      "test.status" : "fail",
      "test.suite" : "[org/example/test_abort_suite] test abort suite",
      "test.traits" : "{\"category\":[\"fail\"]}",
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
    "resource" : "[org/example/test_abort_suite] test abort suite.aborting scenario",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_parent_id},
    "start" : ${content_start_4},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id}
  },
  "type" : "test",
  "version" : 2
}, {
  "content" : {
    "duration" : ${content_duration_5},
    "error" : 0,
    "meta" : {
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
      "test.command" : "karate-2.0",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.status" : "fail",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "karate.test_session",
    "resource" : "karate-2.0",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_5},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_6},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "component" : "karate",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.framework" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "karate-2.0",
      "test.status" : "fail",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_4}
    },
    "name" : "karate.test_module",
    "resource" : "karate-2.0",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_6},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
} ]