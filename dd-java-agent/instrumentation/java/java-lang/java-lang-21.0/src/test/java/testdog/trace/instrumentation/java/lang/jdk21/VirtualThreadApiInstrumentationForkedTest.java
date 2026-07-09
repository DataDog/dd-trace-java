package testdog.trace.instrumentation.java.lang.jdk21;

import datadog.trace.junit.utils.config.WithConfig;

/**
 * Runs the {@link VirtualThreadApiInstrumentationTest} cases with the legacy context manager
 * disabled, so {@code VirtualThreadState} takes the swap path instead of seed-once. Forked because
 * the legacy-context-manager choice is captured once per JVM.
 */
@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class VirtualThreadApiInstrumentationForkedTest extends VirtualThreadApiInstrumentationTest {}
