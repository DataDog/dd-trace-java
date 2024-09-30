[ {
  "type" : "test_session_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "maven.test_session",
    "resource" : "Maven Integration Tests Project",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 1,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "test.type" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.status" : "fail",
      "test_session.name" : "session-name",
      "language" : "jvm",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "maven",
      "error.type" : "org.apache.maven.lifecycle.LifecycleExecutionException",
      "_dd.profiling.ctx" : "test",
      "test.toolchain" : ${content_meta_test_toolchain},
      "span.kind" : "test_session_end",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "runtime-id" : ${content_meta_runtime_id},
      "test.command" : "mvn -B clean test"
    }
  }
}, {
  "type" : "test_module_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "maven.test_module",
    "resource" : "Maven Integration Tests Project maven-surefire-plugin default-test",
    "start" : ${content_start_2},
    "duration" : ${content_duration_2},
    "error" : 1,
    "metrics" : { },
    "meta" : {
      "test.type" : "test",
      "test.module" : "Maven Integration Tests Project maven-surefire-plugin default-test",
      "test.status" : "fail",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "maven",
      "error.type" : "org.apache.maven.lifecycle.LifecycleExecutionException",
      "span.kind" : "test_module_end",
      "test.execution" : "maven-surefire-plugin:test:default-test",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack_2},
      "test.command" : "mvn -B clean test"
    }
  }
} ]