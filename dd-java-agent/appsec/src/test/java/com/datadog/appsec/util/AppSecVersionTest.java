package com.datadog.appsec.util;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class AppSecVersionTest {

  @Test
  void allFieldsHaveAValue() {
    // expect
    assertNotNull(AppSecVersion.JAVA_VERSION);
    assertNotNull(AppSecVersion.JAVA_VM_NAME);
    assertNotNull(AppSecVersion.JAVA_VM_VENDOR);
    assertNotNull(AppSecVersion.VERSION);
    assertNotEquals("unknown", AppSecVersion.VERSION);
  }
}
