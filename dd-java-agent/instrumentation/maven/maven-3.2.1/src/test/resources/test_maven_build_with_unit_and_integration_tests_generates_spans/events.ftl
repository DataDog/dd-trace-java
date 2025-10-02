[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "maven",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test_session_end",
      "test.command" : "mvn -B verify",
      "test.status" : "pass",
      "test.toolchain" : ${content_meta_test_toolchain},
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "maven.test_session",
    "resource" : "Maven Integration Tests Project",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
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
      "component" : "maven",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.command" : "mvn -B verify",
      "test.execution" : "maven-failsafe-plugin:integration-test:default",
      "test.module" : "Maven Integration Tests Project maven-failsafe-plugin default",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2}
    },
    "name" : "maven.test_module",
    "resource" : "Maven Integration Tests Project maven-failsafe-plugin default",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
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
      "component" : "maven",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.command" : "mvn -B verify",
      "test.execution" : "maven-surefire-plugin:test:default-test",
      "test.module" : "Maven Integration Tests Project maven-surefire-plugin default-test",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3}
    },
    "name" : "maven.test_module",
    "resource" : "Maven Integration Tests Project maven-surefire-plugin default-test",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_3},
    "test_module_id" : ${content_test_module_id_2},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_4},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_4},
      "env" : "none",
      "execution" : "add-test-source",
      "library_version" : ${content_meta_library_version},
      "plugin" : "build-helper-maven-plugin",
      "project" : "Maven Integration Tests Project"
    },
    "metrics" : { },
    "name" : "Maven_Integration_Tests_Project_build_helper_maven_plugin_add_test_source",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Integration_Tests_Project_build_helper_maven_plugin_add_test_source",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id},
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
      "env" : "none",
      "execution" : "default-compile",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-compiler-plugin",
      "project" : "Maven Integration Tests Project"
    },
    "metrics" : { },
    "name" : "Maven_Integration_Tests_Project_maven_compiler_plugin_default_compile",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Integration_Tests_Project_maven_compiler_plugin_default_compile",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_2},
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
      "env" : "none",
      "execution" : "default-testCompile",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-compiler-plugin",
      "project" : "Maven Integration Tests Project"
    },
    "metrics" : { },
    "name" : "Maven_Integration_Tests_Project_maven_compiler_plugin_default_testCompile",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Integration_Tests_Project_maven_compiler_plugin_default_testCompile",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_3},
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
      "env" : "none",
      "execution" : "default",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-failsafe-plugin",
      "project" : "Maven Integration Tests Project"
    },
    "metrics" : { },
    "name" : "Maven_Integration_Tests_Project_maven_failsafe_plugin_default",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Integration_Tests_Project_maven_failsafe_plugin_default",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_4},
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
      "env" : "none",
      "execution" : "default-jar",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-jar-plugin",
      "project" : "Maven Integration Tests Project"
    },
    "metrics" : { },
    "name" : "Maven_Integration_Tests_Project_maven_jar_plugin_default_jar",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Integration_Tests_Project_maven_jar_plugin_default_jar",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_5},
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
      "env" : "none",
      "execution" : "default-resources",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-resources-plugin",
      "project" : "Maven Integration Tests Project"
    },
    "metrics" : { },
    "name" : "Maven_Integration_Tests_Project_maven_resources_plugin_default_resources",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Integration_Tests_Project_maven_resources_plugin_default_resources",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_6},
    "start" : ${content_start_9},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_10},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_10},
      "env" : "none",
      "execution" : "default-testResources",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-resources-plugin",
      "project" : "Maven Integration Tests Project"
    },
    "metrics" : { },
    "name" : "Maven_Integration_Tests_Project_maven_resources_plugin_default_testResources",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Integration_Tests_Project_maven_resources_plugin_default_testResources",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_7},
    "start" : ${content_start_10},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
} ]