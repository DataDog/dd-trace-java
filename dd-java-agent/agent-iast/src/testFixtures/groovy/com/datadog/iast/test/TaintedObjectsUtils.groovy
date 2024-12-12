package com.datadog.iast.test

import com.datadog.iast.taint.TaintedMap
import com.datadog.iast.taint.TaintedObjectsMap
import datadog.trace.api.iast.taint.TaintedObjects

class TaintedObjectsUtils {

  private static final int TEST_TAINTED_MAP_SIZE = 1 << 8

  static TaintedObjects taintedObjects() {
    return TaintedObjectsMap.build(TaintedMap.build(TEST_TAINTED_MAP_SIZE))
  }

  static TaintedObjects noOpTaintedObjects() {
    return TaintedObjectsMap.build(TaintedMap.NoOp.INSTANCE)
  }
}
