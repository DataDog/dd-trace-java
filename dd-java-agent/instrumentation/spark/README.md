# Spark application Span Links

Independent non-streaming Spark application traces are opt-in. They are enabled when either
`DD_DATA_JOBS_SPARK_SPAN_LINKS` (`dd.data.jobs.spark.span-links`) or
`DD_DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED` is `true`; both default to `false`. Spark/Data Jobs
instrumentation must still be enabled separately. The effective mode is captured when the driver
listener is constructed, so rollback requires disabling both flags and restarting the driver.

In enabled mode, one `spark.application` root represents the lifetime of a non-streaming Spark
application. OpenLineage execution context is linked from that root. Databricks task context is
read per job and linked from each non-streaming `spark.sql` entry, or from the direct `spark.job`
entry when there is no SQL span. This placement is important for shared drivers, where task
identity changes while the Spark application remains the same.

Structured Streaming and mixed batch/streaming drivers are not part of this rollout. The optional
`spark-executor` instrumentation also remains separate and does not join executor diagnostic
traces to the driver trace.

Delivery uses the tracer's existing partial-flush settings. A lower threshold can make completed
children visible while the application root is open. With partial flush disabled, or below the
configured threshold, completed children may remain buffered until application termination.
