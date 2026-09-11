package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_MEMORY_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_MEMORY_ENABLED_DEFAULT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the config-key plumbing used by ByteBufferInstrumentation and
 * DirectByteBufferInstrumentation. Platform guards (Java version, JFR availability) are
 * exercised by the Groovy integration specs; these tests focus on the config-resolution contract.
 */
class ByteBufferInstrumentationConfigTest {

  @Test
  void newKeyFalseDisablesDirectMemoryProfiling() {
    Properties props = new Properties();
    props.put(PROFILING_DIRECT_MEMORY_ENABLED, "false");
    props.put(PROFILING_DIRECT_ALLOCATION_ENABLED, "true");
    ConfigProvider cp = ConfigProvider.withPropertiesOverride(props);
    assertFalse(
        cp.getBoolean(
            PROFILING_DIRECT_MEMORY_ENABLED,
            PROFILING_DIRECT_MEMORY_ENABLED_DEFAULT,
            PROFILING_DIRECT_ALLOCATION_ENABLED),
        "New key false must take priority over legacy key true");
  }

  @Test
  void legacyKeyOnlyFallbackEnablesDirectMemoryProfiling() {
    Properties props = new Properties();
    props.put(PROFILING_DIRECT_ALLOCATION_ENABLED, "true");
    ConfigProvider cp = ConfigProvider.withPropertiesOverride(props);
    assertTrue(
        cp.getBoolean(
            PROFILING_DIRECT_MEMORY_ENABLED,
            PROFILING_DIRECT_MEMORY_ENABLED_DEFAULT,
            PROFILING_DIRECT_ALLOCATION_ENABLED),
        "Legacy alias must act as fallback when new key is absent");
  }

  @Test
  void legacyKeyFalseWithNewKeyAbsentDisablesDirectMemoryProfiling() {
    Properties props = new Properties();
    props.put(PROFILING_DIRECT_ALLOCATION_ENABLED, "false");
    ConfigProvider cp = ConfigProvider.withPropertiesOverride(props);
    assertFalse(
        cp.getBoolean(
            PROFILING_DIRECT_MEMORY_ENABLED,
            PROFILING_DIRECT_MEMORY_ENABLED_DEFAULT,
            PROFILING_DIRECT_ALLOCATION_ENABLED),
        "Legacy alias false must disable profiling when new key is absent");
  }
}
