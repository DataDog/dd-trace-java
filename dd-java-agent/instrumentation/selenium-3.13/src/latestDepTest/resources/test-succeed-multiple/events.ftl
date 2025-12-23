[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "component" : "junit5",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_suite_end",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.framework" : "junit5",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "selenium",
      "test.source.file" : "dummy_source_path",
      "test.status" : "pass",
      "test.suite" : "org.example.TestSucceedMultipleSelenium",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "test.source.end" : 19,
      "test.source.start" : 11
    },
    "name" : "junit5.test_suite",
    "resource" : "org.example.TestSucceedMultipleSelenium",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id}
  },
  "type" : "test_suite_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_2},
    "error" : 0,
    "meta" : {
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "junit5",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.browser.driver" : "selenium",
      "test.browser.driver_version" : ${content_meta_test_browser_driver_version},
      "test.browser.name" : "htmlunit",
      "test.browser.version" : ${content_meta_test_browser_version},
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.framework" : "junit5",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.is_rum_active" : "true",
      "test.module" : "selenium",
      "test.name" : "test_succeed",
      "test.source.file" : "dummy_source_path",
      "test.source.method" : "test_succeed()V",
      "test.status" : "pass",
      "test.suite" : "org.example.TestSucceedMultipleSelenium",
      "test.type" : "browser",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.source.end" : 18,
      "test.source.start" : 12
    },
    "name" : "junit5.test",
    "parent_id" : ${content_parent_id},
    "resource" : "org.example.TestSucceedMultipleSelenium.test_succeed",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id},
    "start" : ${content_start_2},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id}
  },
  "type" : "test",
  "version" : 2
}, {
  "content" : {
    "duration" : ${content_duration_3},
    "error" : 0,
    "meta" : {
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "junit5",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.browser.driver" : "selenium",
      "test.browser.driver_version" : ${content_meta_test_browser_driver_version},
      "test.browser.name" : "htmlunit",
      "test.browser.version" : ${content_meta_test_browser_version},
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.framework" : "junit5",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.is_rum_active" : "true",
      "test.module" : "selenium",
      "test.name" : "test_succeed_another",
      "test.source.file" : "dummy_source_path",
      "test.source.method" : "test_succeed_another()V",
      "test.status" : "pass",
      "test.suite" : "org.example.TestSucceedMultipleSelenium",
      "test.type" : "browser",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.source.end" : 18,
      "test.source.start" : 12
    },
    "name" : "junit5.test",
    "parent_id" : ${content_parent_id},
    "resource" : "org.example.TestSucceedMultipleSelenium.test_succeed_another",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_2},
    "start" : ${content_start_3},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id_2}
  },
  "type" : "test",
  "version" : 2
}, {
  "content" : {
    "duration" : ${content_duration_4},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "junit5",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test_session_end",
      "test.command" : "selenium",
      "test.framework" : "junit5",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_4},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "junit5.test_session",
    "resource" : "selenium",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_4},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_5},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "component" : "junit5",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.framework" : "junit5",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "selenium",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_5}
    },
    "name" : "junit5.test_module",
    "resource" : "selenium",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_5},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
} ]