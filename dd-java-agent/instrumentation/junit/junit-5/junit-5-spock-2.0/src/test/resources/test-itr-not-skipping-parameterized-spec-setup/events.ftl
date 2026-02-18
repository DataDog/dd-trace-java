[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "_dd.ci.itr.tests_skipped" : "true",
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "spock",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test_session_end",
      "test.command" : "junit-5-spock-2.0",
      "test.framework" : "spock",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.itr.tests_skipping.enabled" : "true",
      "test.itr.tests_skipping.type" : "test",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.itr.tests_skipping.count" : 1
    },
    "name" : "spock.test_session",
    "resource" : "junit-5-spock-2.0",
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
      "_dd.ci.itr.tests_skipped" : "true",
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "component" : "spock",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.framework" : "spock",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.itr.tests_skipping.enabled" : "true",
      "test.itr.tests_skipping.type" : "test",
      "test.module" : "junit-5-spock-2.0",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2},
      "test.itr.tests_skipping.count" : 1
    },
    "name" : "spock.test_module",
    "resource" : "junit-5-spock-2.0",
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
      "component" : "spock",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_suite_end",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.framework" : "spock",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "junit-5-spock-2.0",
      "test.source.file" : "dummy_source_path",
      "test.status" : "pass",
      "test.suite" : "org.example.TestParameterizedSetupSpecSpock",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "test.source.end" : 19,
      "test.source.start" : 11
    },
    "name" : "spock.test_suite",
    "resource" : "org.example.TestParameterizedSetupSpecSpock",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_3},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id}
  },
  "type" : "test_suite_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_4},
    "error" : 0,
    "itr_correlation_id" : "itrCorrelationId",
    "meta" : {
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "spock",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.final_status" : "skip",
      "test.framework" : "spock",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "junit-5-spock-2.0",
      "test.name" : "test add 1 and 2",
      "test.parameters" : "{\"metadata\":{\"test_name\":\"test add 1 and 2\"}}",
      "test.skip_reason" : "Skipped by Datadog Test Impact Analysis",
      "test.skipped_by_itr" : "true",
      "test.source.file" : "dummy_source_path",
      "test.source.method" : "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
      "test.status" : "skip",
      "test.suite" : "org.example.TestParameterizedSetupSpecSpock",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_4},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.source.end" : 18,
      "test.source.start" : 12
    },
    "name" : "spock.test",
    "parent_id" : ${content_parent_id},
    "resource" : "org.example.TestParameterizedSetupSpecSpock.test add 1 and 2",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id},
    "start" : ${content_start_4},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id}
  },
  "type" : "test",
  "version" : 2
}, {
  "content" : {
    "duration" : ${content_duration_5},
    "error" : 0,
    "itr_correlation_id" : "itrCorrelationId",
    "meta" : {
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "spock",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "test.final_status" : "pass",
      "test.framework" : "spock",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "junit-5-spock-2.0",
      "test.name" : "test add 4 and 4",
      "test.parameters" : "{\"metadata\":{\"test_name\":\"test add 4 and 4\"}}",
      "test.source.file" : "dummy_source_path",
      "test.source.method" : "test add #a and #b(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
      "test.status" : "pass",
      "test.suite" : "org.example.TestParameterizedSetupSpecSpock",
      "test.type" : "test",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_5},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.source.end" : 18,
      "test.source.start" : 12
    },
    "name" : "spock.test",
    "parent_id" : ${content_parent_id},
    "resource" : "org.example.TestParameterizedSetupSpecSpock.test add 4 and 4",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_2},
    "start" : ${content_start_5},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id_2}
  },
  "type" : "test",
  "version" : 2
}, {
  "content" : {
    "duration" : ${content_duration_6},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_4},
      "env" : "none",
      "library_version" : ${content_meta_library_version}
    },
    "metrics" : { },
    "name" : "spec_setup",
    "parent_id" : ${content_test_suite_id},
    "resource" : "spec_setup",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "span_id" : ${content_span_id_3},
    "start" : ${content_start_6},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
} ]