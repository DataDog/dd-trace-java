[ {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_trace_id},
    "span_id" : ${content_span_id},
    "parent_id" : ${content_parent_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.step",
    "resource" : "* assert false",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 0,
    "metrics" : {
      "step.endLine" : 7,
      "step.startLine" : 7
    },
    "meta" : {
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "step.name" : "* assert false",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_trace_id_2},
    "span_id" : ${content_span_id_2},
    "parent_id" : ${content_parent_id_2},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.step",
    "resource" : "* print 'first'",
    "start" : ${content_start_2},
    "duration" : ${content_duration_2},
    "error" : 0,
    "metrics" : {
      "step.endLine" : 4,
      "step.startLine" : 4
    },
    "meta" : {
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "step.name" : "* print 'first'",
      "env" : "none"
    }
  }
}, {
  "type" : "test_suite_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test_suite",
    "resource" : "[org/example/test_failed] test failed",
    "start" : ${content_start_3},
    "duration" : ${content_duration_3},
    "error" : 1,
    "metrics" : { },
    "meta" : {
      "test.type" : "test",
      "os.architecture" : ${content_meta_os_architecture},
      "test.module" : "karate",
      "test.status" : "fail",
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "os.platform" : ${content_meta_os_platform},
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "error.type" : "com.intuit.karate.KarateException",
      "span.kind" : "test_suite_end",
      "error.message" : ${content_meta_error_message},
      "test.suite" : "[org/example/test_failed] test failed",
      "error.stack" : ${content_meta_error_stack},
      "runtime.version" : ${content_meta_runtime_version},
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate"
    }
  }
}, {
  "type" : "test",
  "version" : 2,
  "content" : {
    "trace_id" : ${content_trace_id_2},
    "span_id" : ${content_parent_id_2},
    "parent_id" : ${content_parent_id_3},
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test",
    "resource" : "[org/example/test_failed] test failed.first scenario",
    "start" : ${content_start_4},
    "duration" : ${content_duration_4},
    "error" : 0,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "os.architecture" : ${content_meta_os_architecture},
      "test.module" : "karate",
      "test.status" : "pass",
      "language" : "jvm",
      "runtime.name" : ${content_meta_runtime_name},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "test.name" : "first scenario",
      "span.kind" : "test",
      "test.suite" : "[org/example/test_failed] test failed",
      "runtime.version" : ${content_meta_runtime_version},
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "component" : "karate",
      "_dd.profiling.ctx" : "test",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate"
    }
  }
}, {
  "type" : "test",
  "version" : 2,
  "content" : {
    "trace_id" : ${content_trace_id},
    "span_id" : ${content_parent_id},
    "parent_id" : ${content_parent_id_3},
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test",
    "resource" : "[org/example/test_failed] test failed.second scenario",
    "start" : ${content_start_5},
    "duration" : ${content_duration_5},
    "error" : 1,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "os.architecture" : ${content_meta_os_architecture},
      "test.module" : "karate",
      "test.status" : "fail",
      "language" : "jvm",
      "runtime.name" : ${content_meta_runtime_name},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "test.name" : "second scenario",
      "span.kind" : "test",
      "test.suite" : "[org/example/test_failed] test failed",
      "runtime.version" : ${content_meta_runtime_version},
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "component" : "karate",
      "error.type" : "com.intuit.karate.KarateException",
      "_dd.profiling.ctx" : "test",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack_2},
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
    "start" : ${content_start_6},
    "duration" : ${content_duration_6},
    "error" : 0,
    "metrics" : {
      "process_id" : ${content_metrics_process_id},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0
    },
    "meta" : {
      "test.type" : "test",
      "os.architecture" : ${content_meta_os_architecture},
      "test.status" : "fail",
      "language" : "jvm",
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "os.platform" : ${content_meta_os_platform},
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "_dd.profiling.ctx" : "test",
      "span.kind" : "test_session_end",
      "runtime.version" : ${content_meta_runtime_version},
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
    "start" : ${content_start_7},
    "duration" : ${content_duration_7},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "test.type" : "test",
      "os.architecture" : ${content_meta_os_architecture},
      "test.module" : "karate",
      "test.status" : "fail",
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "os.platform" : ${content_meta_os_platform},
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "span.kind" : "test_module_end",
      "runtime.version" : ${content_meta_runtime_version},
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "karate"
    }
  }
} ]