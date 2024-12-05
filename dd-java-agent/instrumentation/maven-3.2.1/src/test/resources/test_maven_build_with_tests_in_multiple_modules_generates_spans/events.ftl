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
      "test_session.name" : "session-name",
      "language" : "jvm",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "maven",
      "error.type" : "org.apache.maven.lifecycle.LifecycleExecutionException",
      "_dd.profiling.ctx" : "test",
      "test.toolchain" : ${content_meta_test_toolchain},
      "span.kind" : "test_session_end",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack},
      "runtime-id" : ${content_meta_runtime_id},
      "test.command" : "mvn -B clean test"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "Maven_Integration_Tests_Project_maven_clean_plugin_default_clean",
    "resource" : "Maven_Integration_Tests_Project_maven_clean_plugin_default_clean",
    "start" : ${content_start_2},
    "duration" : ${content_duration_2},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "execution" : "default-clean",
      "project" : "Maven Integration Tests Project",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-clean-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "test_module_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "maven.test_module",
    "resource" : "module-a maven-surefire-plugin default-test",
    "start" : ${content_start_3},
    "duration" : ${content_duration_3},
    "error" : 0,
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2}
    },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "test.type" : "test",
      "test.module" : "module-a maven-surefire-plugin default-test",
      "test.status" : "pass",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "maven",
      "span.kind" : "test_module_end",
      "test.execution" : "maven-surefire-plugin:test:default-test",
      "test.command" : "mvn -B clean test"
    }
  }
}, {
  "type" : "test_module_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id_2},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "maven.test_module",
    "resource" : "module-b maven-surefire-plugin default-test",
    "start" : ${content_start_4},
    "duration" : ${content_duration_4},
    "error" : 1,
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3}
    },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_4},
      "test.type" : "test",
      "test.module" : "module-b maven-surefire-plugin default-test",
      "test.status" : "fail",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "maven",
      "error.type" : "org.apache.maven.lifecycle.LifecycleExecutionException",
      "span.kind" : "test_module_end",
      "test.execution" : "maven-surefire-plugin:test:default-test",
      "error.message" : ${content_meta_error_message},
      "error.stack" : ${content_meta_error_stack_2},
      "test.command" : "mvn -B clean test"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_2},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_a_maven_clean_plugin_default_clean",
    "resource" : "module_a_maven_clean_plugin_default_clean",
    "start" : ${content_start_5},
    "duration" : ${content_duration_5},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_5},
      "execution" : "default-clean",
      "project" : "module-a",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-clean-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_3},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_a_maven_compiler_plugin_default_compile",
    "resource" : "module_a_maven_compiler_plugin_default_compile",
    "start" : ${content_start_6},
    "duration" : ${content_duration_6},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_6},
      "execution" : "default-compile",
      "project" : "module-a",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-compiler-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_4},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_a_maven_compiler_plugin_default_testCompile",
    "resource" : "module_a_maven_compiler_plugin_default_testCompile",
    "start" : ${content_start_7},
    "duration" : ${content_duration_7},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_7},
      "execution" : "default-testCompile",
      "project" : "module-a",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-compiler-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_5},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_a_maven_resources_plugin_default_resources",
    "resource" : "module_a_maven_resources_plugin_default_resources",
    "start" : ${content_start_8},
    "duration" : ${content_duration_8},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_8},
      "execution" : "default-resources",
      "project" : "module-a",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-resources-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_6},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_a_maven_resources_plugin_default_testResources",
    "resource" : "module_a_maven_resources_plugin_default_testResources",
    "start" : ${content_start_9},
    "duration" : ${content_duration_9},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_9},
      "execution" : "default-testResources",
      "project" : "module-a",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-resources-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_7},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_b_maven_clean_plugin_default_clean",
    "resource" : "module_b_maven_clean_plugin_default_clean",
    "start" : ${content_start_10},
    "duration" : ${content_duration_10},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_10},
      "execution" : "default-clean",
      "project" : "module-b",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-clean-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_8},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_b_maven_compiler_plugin_default_compile",
    "resource" : "module_b_maven_compiler_plugin_default_compile",
    "start" : ${content_start_11},
    "duration" : ${content_duration_11},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_11},
      "execution" : "default-compile",
      "project" : "module-b",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-compiler-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_9},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_b_maven_compiler_plugin_default_testCompile",
    "resource" : "module_b_maven_compiler_plugin_default_testCompile",
    "start" : ${content_start_12},
    "duration" : ${content_duration_12},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_12},
      "execution" : "default-testCompile",
      "project" : "module-b",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-compiler-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_10},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_b_maven_resources_plugin_default_resources",
    "resource" : "module_b_maven_resources_plugin_default_resources",
    "start" : ${content_start_13},
    "duration" : ${content_duration_13},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_13},
      "execution" : "default-resources",
      "project" : "module-b",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-resources-plugin",
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_11},
    "parent_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "module_b_maven_resources_plugin_default_testResources",
    "resource" : "module_b_maven_resources_plugin_default_testResources",
    "start" : ${content_start_14},
    "duration" : ${content_duration_14},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_14},
      "execution" : "default-testResources",
      "project" : "module-b",
      "library_version" : ${content_meta_library_version},
      "plugin" : "maven-resources-plugin",
      "env" : "none"
    }
  }
} ]