[ {
  "type" : "test_suite_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "scalatest.test_suite",
    "resource" : "org.example.TestSucceed",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "test.type" : "test",
      "test.source.file" : "dummy_source_path",
      "test.module" : "scalatest",
      "test.status" : "skip",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "scalatest",
      "span.kind" : "test_suite_end",
      "test.suite" : "org.example.TestSucceed",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "scalatest"
    }
  }
}, {
  "type" : "test",
  "version" : 2,
  "content" : {
    "trace_id" : ${content_trace_id},
    "span_id" : ${content_span_id},
    "parent_id" : ${content_parent_id},
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "itr_correlation_id" : "itrCorrelationId",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "scalatest.test",
    "resource" : "org.example.TestSucceed.Example.add adds two numbers",
    "start" : ${content_start_2},
    "duration" : ${content_duration_2},
    "error" : 0,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.source.file" : "dummy_source_path",
      "test.module" : "scalatest",
      "test.status" : "skip",
      "language" : "jvm",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "library_version" : ${content_meta_library_version},
      "test.name" : "Example.add adds two numbers",
      "span.kind" : "test",
      "test.suite" : "org.example.TestSucceed",
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "test.skip_reason" : "Skipped by Datadog Intelligent Test Runner",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "component" : "scalatest",
      "_dd.profiling.ctx" : "test",
      "test.skipped_by_itr" : "true",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "scalatest"
    }
  }
}, {
  "type" : "test_session_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "scalatest.test_session",
    "resource" : "scalatest",
    "start" : ${content_start_3},
    "duration" : ${content_duration_3},
    "error" : 0,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "test.itr.tests_skipping.count" : 1,
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "test.type" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.status" : "skip",
      "_dd.ci.itr.tests_skipped" : "true",
      "test_session.name" : "session-name",
      "language" : "jvm",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "scalatest",
      "_dd.profiling.ctx" : "test",
      "span.kind" : "test_session_end",
      "test.itr.tests_skipping.type" : "test",
      "runtime-id" : ${content_meta_runtime_id},
      "test.command" : "scalatest",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "scalatest",
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
    "name" : "scalatest.test_module",
    "resource" : "scalatest",
    "start" : ${content_start_4},
    "duration" : ${content_duration_4},
    "error" : 0,
    "metrics" : {
      "test.itr.tests_skipping.count" : 1
    },
    "meta" : {
      "test.type" : "test",
      "test.module" : "scalatest",
      "test.status" : "skip",
      "_dd.ci.itr.tests_skipped" : "true",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "scalatest",
      "span.kind" : "test_module_end",
      "test.itr.tests_skipping.type" : "test",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "scalatest",
      "test.itr.tests_skipping.enabled" : "true"
    }
  }
} ]