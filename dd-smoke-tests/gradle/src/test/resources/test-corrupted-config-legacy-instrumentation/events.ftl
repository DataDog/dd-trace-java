[ {
  "type" : "test_session_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "service" : "test-gradle-service",
    "name" : "gradle.test_session",
    "resource" : "gradle-instrumentation-test-project",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 1,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "os.architecture" : ${content_meta_os_architecture},
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.status" : "fail",
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "language" : "jvm",
      "runtime.name" : ${content_meta_runtime_name},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_session_end",
      "runtime.version" : ${content_meta_runtime_version},
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "test_session.name" : "gradle test",
      "env" : "integration-test",
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "component" : "gradle",
      "error.type" : "org.gradle.internal.exceptions.LocationAwareException",
      "test.toolchain" : ${content_meta_test_toolchain},
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "test.command" : "gradle test"
    }
  }
} ]