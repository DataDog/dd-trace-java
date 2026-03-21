package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SchemaSamplerTest {

  @Test
  void schemaSamplerSamplesWithCorrectWeights() {
    long currentTimeMillis = 100000;
    SchemaSampler sampler = new SchemaSampler();

    boolean canSample1 = sampler.canSample(currentTimeMillis);
    int weight1 = sampler.trySample(currentTimeMillis);
    boolean canSample2 = sampler.canSample(currentTimeMillis + 1000);
    boolean canSample3 = sampler.canSample(currentTimeMillis + 2000);
    boolean canSample4 = sampler.canSample(currentTimeMillis + 30000);
    int weight4 = sampler.trySample(currentTimeMillis + 30000);
    boolean canSample5 = sampler.canSample(currentTimeMillis + 30001);

    assertTrue(canSample1);
    assertEquals(1, weight1);
    assertFalse(canSample2);
    assertFalse(canSample3);
    assertTrue(canSample4);
    assertEquals(3, weight4);
    assertFalse(canSample5);
  }
}
