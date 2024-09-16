[ {
  "type" : "test_suite_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test_suite",
    "resource" : "[org/example/test_succeed] test succeed",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "test.type" : "test",
      "test.module" : "karate",
      "test.status" : "skip",
      "test.traits" : "{\"category\":[\"foo\"]}",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "span.kind" : "test_suite_end",
      "test.suite" : "[org/example/test_succeed] test succeed",
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
    "start" : ${content_start_2},
    "duration" : ${content_duration_2},
    "error" : 0,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "test.type" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.status" : "skip",
      "test_session.name" : "session-name",
      "language" : "jvm",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "_dd.profiling.ctx" : "test",
      "span.kind" : "test_session_end",
      "runtime-id" : ${content_meta_runtime_id},
      "test.command" : "karate",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate"
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
    "start" : ${content_start_3},
    "duration" : ${content_duration_3},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "test.type" : "test",
      "test.module" : "karate",
      "test.status" : "skip",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "span.kind" : "test_module_end",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate"
    }
  }
} ]