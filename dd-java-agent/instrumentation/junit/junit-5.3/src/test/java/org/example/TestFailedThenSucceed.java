package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestFailedThenSucceed {

  public static int TEST_EXECUTIONS_COUNT = 0;

  @BeforeEach
  public void setUp() {
    AgentTracer.TracerAPI agentTracer = AgentTracer.get();
    AgentSpan span = agentTracer.buildSpan("junit-manual", "set-up").start();
    try (AgentScope scope = agentTracer.activateManualSpan(span)) {
      // tracing setup to verify that it is executed for every retry
    }
    span.finish();
  }

  @Test
  public void test_failed_then_succeed() {
    assertTrue(++TEST_EXECUTIONS_COUNT > 3);
  }

  @AfterEach
  public void tearDown() {
    AgentTracer.TracerAPI agentTracer = AgentTracer.get();
    AgentSpan span = agentTracer.buildSpan("junit-manual", "tear-down").start();
    try (AgentScope scope = agentTracer.activateManualSpan(span)) {
      // tracing teardown to verify that it is executed for every retry
    }
    span.finish();
  }
}
