package com.datadog.appsec.util

import datadog.trace.test.util.DDSpecification

class AppSecVersionSpecification extends DDSpecification {
  void 'all fields have a value'() {
    expect:
    AppSecVersion.JAVA_VERSION != null
    AppSecVersion.JAVA_VM_NAME != null
    AppSecVersion.JAVA_VM_VENDOR != null
    AppSecVersion.VERSION != null
    AppSecVersion.VERSION != "unknown"
  }
}
