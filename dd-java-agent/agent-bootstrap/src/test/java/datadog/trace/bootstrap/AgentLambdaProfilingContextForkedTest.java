package datadog.trace.bootstrap;

import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Profiling is explicitly unsupported in AWS Lambda runtimes ({@code Agent#startProfilingAgent}
 * bails out there). The AppSec-driven OTel context exposure path must honour the same exclusion, so
 * that enabling AppSec inside a Lambda function never loads the ddprof native library.
 *
 * <p>Forked because {@link WithConfigExtension} swaps the process-wide environment variable
 * provider.
 */
@ExtendWith(WithConfigExtension.class)
class AgentLambdaProfilingContextForkedTest {

  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @WithConfig(key = PROFILING_ENABLED, value = "false")
  @WithConfig(key = PROFILING_DATADOG_PROFILER_ENABLED, value = "true")
  @WithConfig(
      key = "AWS_LAMBDA_FUNCTION_NAME",
      value = "my-function",
      env = true,
      addPrefix = false)
  void doesNotCreateTheDdprofIntegrationInAwsLambda() {
    // The exclusion is only observable when the configuration would otherwise have triggered the
    // ddprof context integration; the Datadog profiler is vetoed on some platforms and JVMs.
    assumeTrue(
        Config.get().isOtelContextExposureEnabled(),
        "OTel context exposure is unavailable on this platform/JVM version");

    // AGENT_CLASSLOADER is null in this unit test, so reaching the ddprof branch at all would fail
    // loudly rather than silently return the no-op integration.
    assertSame(
        ProfilingContextIntegration.NoOp.INSTANCE, Agent.createProfilingContextIntegration());
  }
}
