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
      "os.architecture" : ${content_meta_os_architecture},
      "test.status" : "fail",
      "language" : "jvm",
      "runtime.name" : ${content_meta_runtime_name},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_session_end",
      "runtime.version" : ${content_meta_runtime_version},
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "component" : "maven",
      "error.type" : "org.apache.maven.lifecycle.LifecyclePhaseNotFoundException",
      "_dd.profiling.ctx" : "test",
      "test.toolchain" : ${content_meta_test_toolchain},
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "test.command" : "mvn -B unknownPhase"
    }
  }
} ]