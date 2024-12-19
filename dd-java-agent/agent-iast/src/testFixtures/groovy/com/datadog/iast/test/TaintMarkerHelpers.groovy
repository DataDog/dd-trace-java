package com.datadog.iast.test

import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge

class TaintMarkerHelpers {
  static t(Object o) {
    final propagation = InstrumentationBridge.PROPAGATION
    final to = IastContext.Provider.taintedObjects()
    propagation.isTainted(to, o) ? "$o (tainted)" : o
  }
}
