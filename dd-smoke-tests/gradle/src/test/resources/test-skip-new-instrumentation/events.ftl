[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "gradle",
      "env" : "integration-test",
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
      "test.status" : "skip",
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
    "resource" : ":",
    "service" : "test-gradle-service",
    "start" : ${content_start},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_2},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "_dd.test.is_user_provided_service" : "true",
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "gradle",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test_module_end",
      "test.command" : "gradle test",
      "test.module" : ":test",
      "test.skip_reason" : "NO-SOURCE",
      "test.status" : "skip",
      "test.type" : "test",
      "test_session.name" : "gradle test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2}
    },
    "name" : "gradle.test_module",
    "resource" : ":test",
    "service" : "test-gradle-service",
    "start" : ${content_start_2},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_3},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "classes",
    "parent_id" : ${content_test_session_id},
    "resource" : "classes",
    "service" : "test-gradle-service",
    "span_id" : ${content_span_id},
    "start" : ${content_start_3},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_4},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_4},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "compileJava",
    "parent_id" : ${content_test_session_id},
    "resource" : "compileJava",
    "service" : "test-gradle-service",
    "span_id" : ${content_span_id_2},
    "start" : ${content_start_4},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_5},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_5},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "compileTestJava",
    "parent_id" : ${content_test_session_id},
    "resource" : "compileTestJava",
    "service" : "test-gradle-service",
    "span_id" : ${content_span_id_3},
    "start" : ${content_start_5},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_6},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_6},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "processResources",
    "parent_id" : ${content_test_session_id},
    "resource" : "processResources",
    "service" : "test-gradle-service",
    "span_id" : ${content_span_id_4},
    "start" : ${content_start_6},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_7},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_7},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "processTestResources",
    "parent_id" : ${content_test_session_id},
    "resource" : "processTestResources",
    "service" : "test-gradle-service",
    "span_id" : ${content_span_id_5},
    "start" : ${content_start_7},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_8},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_8},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "testClasses",
    "parent_id" : ${content_test_session_id},
    "resource" : "testClasses",
    "service" : "test-gradle-service",
    "span_id" : ${content_span_id_6},
    "start" : ${content_start_8},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
} ]