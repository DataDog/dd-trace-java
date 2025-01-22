[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 1,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "component" : "scalatest",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "error.type" : "java.lang.RuntimeException",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_suite_end",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.framework" : "scalatest",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "scalatest",
      "test.source.file" : "dummy_source_path",
      "test.status" : "fail",
      "test.suite" : "org.example.TestFailedSuite",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "test.source.end" : 19,
      "test.source.start" : 11
    },
    "name" : "scalatest.test_suite",
    "resource" : "org.example.TestFailedSuite",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id}
  },
  "type" : "test_suite_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_2},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "scalatest",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test_session_end",
      "test.command" : "scalatest",
      "test.framework" : "scalatest",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.status" : "fail",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "scalatest.test_session",
    "resource" : "scalatest",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_2},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_3},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "component" : "scalatest",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.framework" : "scalatest",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "scalatest",
      "test.status" : "fail",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3}
    },
    "name" : "scalatest.test_module",
    "resource" : "scalatest",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_3},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
} ]