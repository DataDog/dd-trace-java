package com.datadog.openfeature.internal.evaluation;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ConfigurationStoreTest {

  @Test
  void startsWithoutAConfiguration() {
    assertNull(new ConfigurationStore().get());
  }
}
