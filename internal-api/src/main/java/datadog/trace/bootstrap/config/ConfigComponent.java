package datadog.trace.bootstrap.config;

import dagger.Component;

@Component(
    dependencies = {
      TracingConfig.class,
      ProfilingConfig.class,
    })
interface ConfigComponent {
  TracingConfig tracing();

  ProfilingConfig profiling();

  @Component.Builder
  interface Builder {
    Builder tracingConfig(TracingConfig tracingConfig);

    Builder profilingConfig(ProfilingConfig profilingConfig);

    ConfigComponent build();
  }
}
