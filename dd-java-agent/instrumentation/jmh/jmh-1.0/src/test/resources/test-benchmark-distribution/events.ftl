[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "component" : "jmh",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_suite_end",
      "test.framework" : "jmh",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "jmh-1.0",
      "test.status" : "pass",
      "test.suite" : "datadog.trace.instrumentation.jmh.benchmarks.DistributionBenchmark",
      "test.type" : "benchmark",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count}
    },
    "name" : "jmh.test_suite",
    "resource" : "datadog.trace.instrumentation.jmh.benchmarks.DistributionBenchmark",
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
      "benchmark.run.mode" : "avgt",
      "benchmark.run.time_unit" : "NANOSECONDS",
      "benchmark.unit" : "ns/op",
      "component" : "jmh",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test",
      "test.final_status" : "pass",
      "test.framework" : "jmh",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "jmh-1.0",
      "test.name" : "measure",
      "test.status" : "pass",
      "test.suite" : "datadog.trace.instrumentation.jmh.benchmarks.DistributionBenchmark",
      "test.type" : "benchmark",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "benchmark.max" : ${content_metrics_benchmark_max},
      "benchmark.min" : ${content_metrics_benchmark_min},
      "benchmark.p50" : ${content_metrics_benchmark_p50},
      "benchmark.p90" : ${content_metrics_benchmark_p90},
      "benchmark.p95" : ${content_metrics_benchmark_p95},
      "benchmark.p99" : ${content_metrics_benchmark_p99},
      "benchmark.run.forks" : 0,
      "benchmark.run.iterations" : 2,
      "benchmark.run.threads" : 1,
      "benchmark.run.warmup_iterations" : 1,
      "benchmark.sample_count" : ${content_metrics_benchmark_sample_count},
      "benchmark.value" : ${content_metrics_benchmark_value},
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "jmh.test",
    "parent_id" : ${content_parent_id},
    "resource" : "datadog.trace.instrumentation.jmh.benchmarks.DistributionBenchmark.measure",
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
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "_dd.profiling.ctx" : "test",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "component" : "jmh",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "runtime-id" : ${content_meta_runtime_id},
      "span.kind" : "test_session_end",
      "test.command" : "jmh-1.0",
      "test.framework" : "jmh",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.status" : "pass",
      "test.type" : "benchmark",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id}
    },
    "name" : "jmh.test_session",
    "resource" : "jmh-1.0",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_3},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_4},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "component" : "jmh",
      "dummy_ci_tag" : "dummy_ci_tag_value",
      "env" : "none",
      "library_version" : ${content_meta_library_version},
      "span.kind" : "test_module_end",
      "test.framework" : "jmh",
      "test.framework_version" : ${content_meta_test_framework_version},
      "test.module" : "jmh-1.0",
      "test.status" : "pass",
      "test.type" : "benchmark",
      "test_session.name" : "session-name"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_4}
    },
    "name" : "jmh.test_module",
    "resource" : "jmh-1.0",
    "service" : "worker.org.gradle.process.internal.worker.gradleworkermain",
    "start" : ${content_start_4},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
} ]