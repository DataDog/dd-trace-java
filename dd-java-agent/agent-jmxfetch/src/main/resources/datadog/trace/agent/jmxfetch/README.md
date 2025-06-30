# Metric Configs

Files from [integrations-core](https://github.com/search?q=repo%3ADataDog%2Fintegrations-core+%22jmx_metrics%3A%22+language%3AYAML&type=code)
are copied here at build time by the `copyMetricConfigs` gradle task after initializing the submodule.

These are then bundled in `dd-java-agent.jar`. Due to limitations in Java jar walking, it is non-trivial
to get all these files from within the jar without knowing their names.
Consequently, we list out each integration in `datadog/trace/agent/jmxfetch/metricconfigs.txt` 
so the agent can reference them.
