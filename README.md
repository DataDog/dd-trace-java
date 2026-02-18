![Datadog logo](https://imgix.datadoghq.com/img/about/presskit/logo-h/dd_horizontal_white.png)

# Datadog Java APM

[![GitHub latest release](https://img.shields.io/github/v/release/datadog/dd-trace-java)](https://github.com/DataDog/dd-trace-java/releases/latest/)
[![GitHub all releases](https://img.shields.io/github/downloads/datadog/dd-trace-java/total)](https://github.com/DataDog/dd-trace-java/releases)
[![GitHub](https://img.shields.io/github/license/datadog/dd-trace-java)](/LICENSE)

This repository contains `dd-trace-java`, Datadog's APM client Java library.
`dd-trace-java` contains APIs to automatically or manually [trace](https://docs.datadoghq.com/tracing/visualization/#trace) and [profile](https://docs.datadoghq.com/tracing/profiler/) Java applications.

These features power [Distributed Tracing](https://docs.datadoghq.com/tracing/) with [Automatic Instrumentation](https://docs.datadoghq.com/tracing/trace_collection/compatibility/java/#integrations),
 [Continuous Profiling](https://docs.datadoghq.com/tracing/profiler/),
 [Error Tracking](https://docs.datadoghq.com/tracing/error_tracking/),
 [Continuous Integration Visibility](https://docs.datadoghq.com/continuous_integration/),
 [Deployment Tracking](https://docs.datadoghq.com/tracing/deployment_tracking/),
 [Code Hotspots](https://docs.datadoghq.com/tracing/profiler/connect_traces_and_profiles/) and more.

## Getting Started

To use and configure, check out the [setup documentation][setup docs].

For advanced usage, check out the [configuration reference][configuration reference] and [custom instrumentation API][api docs].

Confused about the terminology of APM?
Take a look at the [APM Glossary][visualization docs].

[setup docs]: https://docs.datadoghq.com/tracing/languages/java
[configuration reference]: https://docs.datadoghq.com/tracing/trace_collection/library_config/java
[api docs]: https://docs.datadoghq.com/tracing/trace_collection/custom_instrumentation/java/
[visualization docs]: https://docs.datadoghq.com/tracing/visualization/

## Contributing

Before contributing to the project, please take a moment to read our brief [Contribution Guidelines](CONTRIBUTING.md).
Then check our guides:

* [How to set up a development environment and build the project](BUILDING.md),
* [How to create a new instrumentation](docs/add_new_instrumentation.md),
* [How to test](docs/how_to_test.md),

Or our reference documents:

* [How instrumentations work](docs/how_instrumentations_work.md).

## Releases

Datadog will generally release a new minor version during the first full week of every month.

See [release.md](docs/releases.md) for more information.

Additional line
New change in base branch
