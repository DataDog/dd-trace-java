package com.datadog.iast.test

import com.datadog.iast.IastRequestContext
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.iast.IastContext
import okhttp3.OkHttpClient

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class IastRequestTestRunner extends IastAgentTestRunner implements IastRequestContextPreparationTrait {

  private static final LinkedBlockingQueue<TaintedObjectCollection> TAINTED_OBJECTS = new LinkedBlockingQueue<>()

  OkHttpClient client = OkHttpUtils.client(true)

  @Override
  protected Closure getRequestEndAction() {
    { RequestContext requestContext, IGSpanInfo igSpanInfo ->
      // request end action
      IastRequestContext iastRequestContext = IastContext.Provider.get(requestContext)
      if (iastRequestContext) {
        TaintedObjects taintedObjects = iastRequestContext.getTaintedObjects()
        TAINTED_OBJECTS.offer(new TaintedObjectCollection(taintedObjects))
      }
    }
  }

  void cleanup() {
    TAINTED_OBJECTS.clear()
  }

  protected TaintedObjectCollection getFinReqTaintedObjects() {
    TAINTED_OBJECTS.poll(15, TimeUnit.SECONDS)
  }
}
