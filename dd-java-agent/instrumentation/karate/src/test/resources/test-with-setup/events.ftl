[ {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_trace_id},
    "span_id" : ${content_span_id},
    "parent_id" : ${content_parent_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.step",
    "resource" : "* print foo",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 0,
    "metrics" : {
      "step.endLine" : 15,
      "step.startLine" : 15
    },
    "meta" : {
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "step.name" : "* print foo",
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
    "resource" : "* print foo",
    "start" : ${content_start_2},
    "duration" : ${content_duration_2},
    "error" : 0,
    "metrics" : {
      "step.endLine" : 21,
      "step.startLine" : 21
    },
    "meta" : {
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "step.name" : "* print foo",
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
    "resource" : "[org/example/test_with_setup] test with setup",
    "start" : ${content_start_3},
    "duration" : ${content_duration_3},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "test.type" : "test",
      "os.architecture" : ${content_meta_os_architecture},
      "test.module" : "karate",
      "test.status" : "pass",
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "os.platform" : ${content_meta_os_platform},
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "os.version" : ${content_meta_os_version},
      "library_version" : ${content_meta_library_version},
      "component" : "karate",
      "span.kind" : "test_suite_end",
      "test.suite" : "[org/example/test_with_setup] test with setup",
      "runtime.version" : ${content_meta_runtime_version},
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
    "resource" : "[org/example/test_with_setup] test with setup.first scenario",
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
      "test.suite" : "[org/example/test_with_setup] test with setup",
      "runtime.version" : ${content_meta_runtime_version},
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "test.traits" : "{\"category\":[\"withSetup\"]}",
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "test.parameters" : "{\"foo\":\"bar\"}",
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
    "trace_id" : ${content_trace_id_2},
    "span_id" : ${content_parent_id_2},
    "parent_id" : ${content_parent_id_3},
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "test_suite_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "karate.test",
    "resource" : "[org/example/test_with_setup] test with setup.second scenario",
    "start" : ${content_start_5},
    "duration" : ${content_duration_5},
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
      "test.name" : "second scenario",
      "span.kind" : "test",
      "test.suite" : "[org/example/test_with_setup] test with setup",
      "runtime.version" : ${content_meta_runtime_version},
      "runtime-id" : ${content_meta_runtime_id},
      "test.type" : "test",
      "test.traits" : "{\"category\":[\"withSetupOnce\"]}",
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "test.parameters" : "{\"foo\":\"bar\"}",
      "component" : "karate",
      "_dd.profiling.ctx" : "test",
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
      "test.status" : "pass",
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
      "test.status" : "pass",
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