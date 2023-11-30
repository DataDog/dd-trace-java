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
      "test.type" : "test",
      "os.architecture" : ${content_meta_os_architecture},
      "test.status" : "fail",
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "language" : "jvm",
      "runtime.name" : ${content_meta_runtime_name},
      "env" : "integration-test",
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "component" : "gradle",
      "error.type" : "org.gradle.internal.exceptions.LocationAwareException",
      "test.toolchain" : ${content_meta_test_toolchain},
      "span.kind" : "test_session_end",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "runtime.version" : ${content_meta_runtime_version},
      "runtime-id" : ${content_meta_runtime_id},
      "test.command" : "gradle test"
    }
  }
} ]