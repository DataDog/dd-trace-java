[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "junit",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test_session_end",
      "test.command" : "junit-4.13",
      "test.framework" : "junit4",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.status" : "fail",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "junit.test_session",
    "resource" : "junit-4.13",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_2},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "component" : "junit",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.framework" : "junit4",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "junit-4.13",
      "test.status" : "fail",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2}
    },
    "name" : "junit.test_module",
    "resource" : "junit-4.13",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_2},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_3},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "component" : "junit",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_suite_end",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.framework" : "junit4",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "junit-4.13",
      "test.source.file" : "dummy_source_path",
      "test.status" : "fail",
      "test.suite" : "org.example.TestFailedAfter",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "test.source.end" : 19,
      "test.source.start" : 11
    },
    "name" : "junit.test_suite",
    "resource" : "org.example.TestFailedAfter",
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
      "component" : "junit",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "error.type" : "java.lang.RuntimeException",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.framework" : "junit4",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "junit-4.13",
      "test.name" : "another_test_succeed",
      "test.source.file" : "dummy_source_path",
      "test.source.method" : "another_test_succeed()V",
      "test.status" : "fail",
      "test.suite" : "org.example.TestFailedAfter",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_4},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.source.end" : 18,
      "test.source.start" : 12
    },
    "name" : "junit.test",
    "parent_id" : ${content_parent_id},
    "resource" : "org.example.TestFailedAfter.another_test_succeed",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id},
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
    "error" : 1,
    "meta" : {
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "junit",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack_2},
      "error.type" : "java.lang.RuntimeException",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.framework" : "junit4",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "junit-4.13",
      "test.name" : "test_succeed",
      "test.source.file" : "dummy_source_path",
      "test.source.method" : "test_succeed()V",
      "test.status" : "fail",
      "test.suite" : "org.example.TestFailedAfter",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_5},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.source.end" : 18,
      "test.source.start" : 12
    },
    "name" : "junit.test",
    "parent_id" : ${content_parent_id},
    "resource" : "org.example.TestFailedAfter.test_succeed",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_2},
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
    "error" : 1,
    "meta" : {
      "env" : "none",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack_3},
      "error.type" : "java.lang.RuntimeException",
      "library_version" : ${content_meta_library_version},
      "test.callback" : "After"
    },
    "metrics" : { },
    "name" : "tearDown",
    "parent_id" : ${content_span_id_2},
    "resource" : "tearDown",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_3},
    "start" : ${content_start_6},
    "trace_id" : ${content_trace_id_2}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_7},
    "error" : 1,
    "meta" : {
      "env" : "none",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack_4},
      "error.type" : "java.lang.RuntimeException",
      "library_version" : ${content_meta_library_version},
      "test.callback" : "After"
    },
    "metrics" : { },
    "name" : "tearDown",
    "parent_id" : ${content_span_id},
    "resource" : "tearDown",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_4},
    "start" : ${content_start_7},
    "trace_id" : ${content_trace_id}
  },
  "type" : "span",
  "version" : 1
} ]