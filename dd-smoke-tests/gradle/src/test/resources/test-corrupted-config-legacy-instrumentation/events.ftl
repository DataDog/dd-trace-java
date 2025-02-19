[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 1,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "gradle",
      "env" : "integration-test",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "error.type" : "org.gradle.internal.exceptions.LocationAwareException",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test_session_end",
      "test.command" : "gradle test",
      "test.status" : "fail",
      "test.toolchain" : ${content_meta_test_toolchain},
      "test.type" : "test",
      "test_session.name" : "gradle test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "gradle.test_session",
    "resource" : "gradle-instrumentation-test-project",
    "service" : "test-gradle-service",
    "start" : ${content_start},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
} ]