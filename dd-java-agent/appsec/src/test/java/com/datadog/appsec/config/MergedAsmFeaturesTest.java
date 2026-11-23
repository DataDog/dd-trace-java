package com.datadog.appsec.config;

import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION;
import static datadog.trace.api.UserIdCollectionMode.DISABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.UserIdCollectionMode;
import org.junit.jupiter.api.Test;

class MergedAsmFeaturesTest {

  @Test
  void testMergingAndRemovingAsmActivation() {
    // setup
    MergedAsmFeatures features = new MergedAsmFeatures();

    // when
    features.addConfig("asm_activation_1", asm(false));

    // then
    assertFalse(features.getMergedData().asm.enabled);

    // when
    features.addConfig("asm_activation_1", asm(true));

    // then
    assertTrue(features.getMergedData().asm.enabled);
  }

  @Test
  void testMergingAndRemovingAutoUserInstrum() {
    // setup
    MergedAsmFeatures features = new MergedAsmFeatures();

    // when
    features.addConfig("auto_user_instrum_1", autoUserInstrum(ANONYMIZATION));

    // then
    assertEquals(ANONYMIZATION.toString(), features.getMergedData().autoUserInstrum.mode);

    // when
    features.addConfig("auto_user_instrum_1", autoUserInstrum(DISABLED));

    // then
    assertEquals(DISABLED.toString(), features.getMergedData().autoUserInstrum.mode);
  }

  private static AppSecFeatures asm(boolean enabled) {
    AppSecFeatures features = new AppSecFeatures();
    features.asm = new AppSecFeatures.Asm();
    features.asm.enabled = enabled;
    return features;
  }

  private static AppSecFeatures autoUserInstrum(UserIdCollectionMode mode) {
    AppSecFeatures features = new AppSecFeatures();
    features.autoUserInstrum = new AppSecFeatures.AutoUserInstrum();
    features.autoUserInstrum.mode = mode.toString();
    return features;
  }
}
