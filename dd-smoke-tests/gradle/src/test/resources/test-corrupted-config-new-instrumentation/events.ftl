[ {
  "type" : "test_session_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "service" : "test-gradle-service",
    "name" : "gradle.test_session",
    "resource" : ":",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 1,
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "test.type" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "test.status" : "fail",
      "test_session.name" : "gradle test",
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "language" : "jvm",
      "env" : "integration-test",
      "library_version" : ${content_meta_library_version},
      "component" : "gradle",
      "error.type" : "org.gradle.internal.exceptions.LocationAwareException",
      "test.toolchain" : ${content_meta_test_toolchain},
      "span.kind" : "test_session_end",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "runtime-id" : ${content_meta_runtime_id},
      "test.command" : "gradle test",
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version}
    }
  }
} ]