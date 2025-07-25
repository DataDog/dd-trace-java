[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "maven",
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
      "test.code_coverage.enabled" : "true",
      "test.command" : "mvn -B test",
      "test.framework" : "spock",
      "test.framework_version" : "2.4.0-M6-groovy-4.0",
      "test.status" : "pass",
      "test.toolchain" : ${content_meta_test_toolchain},
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "maven.test_session",
    "resource" : "Maven Smoke Tests Project",
    "service" : "test-maven-service",
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
      "component" : "maven",
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
      "test.code_coverage.enabled" : "true",
      "test.command" : "mvn -B test",
      "test.execution" : "maven-surefire-plugin:test:default-test",
      "test.framework" : "spock",
      "test.framework_version" : "2.4.0-M6-groovy-4.0",
      "test.module" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2}
    },
    "name" : "maven.test_module",
    "resource" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
    "service" : "test-maven-service",
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
      "execution" : "default",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "gmavenplus-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_gmavenplus_plugin_default",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_gmavenplus_plugin_default",
    "service" : "test-maven-service",
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
      "execution" : "default",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "gmavenplus-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_gmavenplus_plugin_default",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_gmavenplus_plugin_default",
    "service" : "test-maven-service",
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
      "execution" : "default-compile",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "maven-compiler-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_maven_compiler_plugin_default_compile",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_maven_compiler_plugin_default_compile",
    "service" : "test-maven-service",
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
      "execution" : "default-testCompile",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "maven-compiler-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_maven_compiler_plugin_default_testCompile",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_maven_compiler_plugin_default_testCompile",
    "service" : "test-maven-service",
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
      "execution" : "default-resources",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "maven-resources-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_maven_resources_plugin_default_resources",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_maven_resources_plugin_default_resources",
    "service" : "test-maven-service",
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
      "execution" : "default-testResources",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "maven-resources-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_maven_resources_plugin_default_testResources",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_maven_resources_plugin_default_testResources",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id_6},
    "start" : ${content_start_8},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_9},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_9},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "spock",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id_2},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test_suite_end",
      "test.framework" : "spock",
      "test.framework_version" : "2.4.0-M6-groovy-4.0",
      "test.module" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
      "test.status" : "pass",
      "test.suite" : "test_successful_maven_run_junit_platform_runner.src.test.groovy.SampleSpockTest",
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id_2}
    },
    "name" : "spock.test_suite",
    "resource" : "test_successful_maven_run_junit_platform_runner.src.test.groovy.SampleSpockTest",
    "service" : "test-maven-service",
    "start" : ${content_start_9},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id}
  },
  "type" : "test_suite_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_10},
    "error" : 0,
    "meta" : {
      "_dd.library_capabilities.auto_test_retries" : "1",
      "_dd.library_capabilities.early_flake_detection" : "1",
      "_dd.library_capabilities.impacted_tests" : "1",
      "_dd.library_capabilities.test_impact_analysis" : "1",
      "_dd.library_capabilities.test_management.attempt_to_fix" : "5",
      "_dd.library_capabilities.test_management.disable" : "1",
      "_dd.library_capabilities.test_management.quarantine" : "1",
      "_dd.p.tid" : ${content_meta__dd_p_tid_10},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "spock",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id_2},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test",
      "test.framework" : "spock",
      "test.framework_version" : "2.4.0-M6-groovy-4.0",
      "test.module" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
      "test.name" : "test should pass",
      "test.source.method" : "test should pass()V",
      "test.status" : "pass",
      "test.suite" : "test_successful_maven_run_junit_platform_runner.src.test.groovy.SampleSpockTest",
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_4},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id_2}
    },
    "name" : "spock.test",
    "parent_id" : ${content_parent_id},
    "resource" : "test_successful_maven_run_junit_platform_runner.src.test.groovy.SampleSpockTest.test should pass",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id_7},
    "start" : ${content_start_10},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id}
  },
  "type" : "test",
  "version" : 2
} ]
