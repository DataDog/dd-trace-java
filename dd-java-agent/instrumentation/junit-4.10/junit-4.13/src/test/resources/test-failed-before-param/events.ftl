[ {
  "type" : "test_session_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "junit.test_session",
    "resource" : "junit-4.13",
    "start" : ${content_start},
    "duration" : ${content_duration},
    "error" : 0,
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
      "test.status" : "skip",
      "test_session.name" : "session-name",
      "language" : "jvm",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "junit",
      "_dd.profiling.ctx" : "test",
      "span.kind" : "test_session_end",
      "runtime-id" : ${content_meta_runtime_id},
      "test.command" : "junit-4.13",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "junit4"
    }
  }
}, {
  "type" : "test_module_end",
  "version" : 1,
  "content" : {
    "test_session_id" : ${content_test_session_id},
    "test_module_id" : ${content_test_module_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "junit.test_module",
    "resource" : "junit-4.13",
    "start" : ${content_start_2},
    "duration" : ${content_duration_2},
    "error" : 0,
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2}
    },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "test.type" : "test",
      "test.module" : "junit-4.13",
      "test.status" : "skip",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "library_version" : ${content_meta_library_version},
      "component" : "junit",
      "span.kind" : "test_module_end",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "junit4"
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
    "name" : "junit.test_suite",
    "resource" : "org.example.TestFailedBeforeParam",
    "start" : ${content_start_3},
    "duration" : ${content_duration_3},
    "error" : 0,
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "test.source.end" : 19,
      "test.source.start" : 11
    },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "test.type" : "test",
      "test.source.file" : "dummy_source_path",
      "test.module" : "junit-4.13",
      "test.status" : "skip",
      "test_session.name" : "session-name",
      "env" : "none",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "test.codeowners" : "[\"owner1\",\"owner2\"]",
      "library_version" : ${content_meta_library_version},
      "component" : "junit",
      "span.kind" : "test_suite_end",
      "test.suite" : "org.example.TestFailedBeforeParam",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.framework" : "junit4"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id},
    "parent_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "setup",
    "resource" : "setup",
    "start" : ${content_start_4},
    "duration" : ${content_duration_4},
    "error" : 1,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_4},
      "error.stack" : ${content_meta_error_stack},
      "test.callback" : "BeforeParam",
      "library_version" : ${content_meta_library_version},
      "error.type" : "java.lang.RuntimeException",
      "env" : "none",
      "error.message" : ${content_meta_error_message}
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_2},
    "parent_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "setup",
    "resource" : "setup",
    "start" : ${content_start_5},
    "duration" : ${content_duration_5},
    "error" : 1,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_5},
      "error.stack" : ${content_meta_error_stack_2},
      "test.callback" : "BeforeParam",
      "library_version" : ${content_meta_library_version},
      "error.type" : "java.lang.RuntimeException",
      "env" : "none",
      "error.message" : ${content_meta_error_message}
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_3},
    "parent_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "tearDown",
    "resource" : "tearDown",
    "start" : ${content_start_6},
    "duration" : ${content_duration_6},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_6},
      "test.callback" : "AfterParam",
      "library_version" : ${content_meta_library_version},
      "env" : "none"
    }
  }
}, {
  "type" : "span",
  "version" : 1,
  "content" : {
    "trace_id" : ${content_test_session_id},
    "span_id" : ${content_span_id_4},
    "parent_id" : ${content_test_suite_id},
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "name" : "tearDown",
    "resource" : "tearDown",
    "start" : ${content_start_7},
    "duration" : ${content_duration_7},
    "error" : 0,
    "metrics" : { },
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_7},
      "test.callback" : "AfterParam",
      "library_version" : ${content_meta_library_version},
      "env" : "none"
    }
  }
} ]