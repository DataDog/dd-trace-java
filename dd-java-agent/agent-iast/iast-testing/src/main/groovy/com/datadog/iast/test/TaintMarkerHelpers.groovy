package com.datadog.iast.test

import datadog.trace.api.iast.InstrumentationBridge

class TaintMarkerHelpers {
  static t(Object o) {
    def propagation = InstrumentationBridge.PROPAGATION
    propagation.isTainted(o) ? "$o (tainted)" : o
  }
}
