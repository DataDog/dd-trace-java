package com.datadog.iast.test

import datadog.trace.api.iast.InstrumentationBridge

class TaintMarkerHelpers {
  // if 'o' is an Iterable, it's reported as tainted only when every element is tainted.
  // 'label' defaults to 'o' itself, but can be overridden when 'o' has no useful toString
  static t(Object o, String label = "$o") {
    def propagation = InstrumentationBridge.PROPAGATION
    def tainted = o instanceof Iterable ? o.every { propagation.isTainted(it) } : propagation.isTainted(o)
    tainted ? label + ' (tainted)' : label
  }
}
