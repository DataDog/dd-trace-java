package com.datadog.iast.test

import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import groovy.json.JsonBuilder
import groovy.transform.CompileStatic

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

abstract class IastHttpServerTest<SERVER> extends WithHttpServer<SERVER> implements IastRequestContextPreparationTrait {

  private static final LinkedBlockingQueue<TaintedObjectCollection> TAINTED_OBJECTS = new LinkedBlockingQueue<>()

  private static final LinkedBlockingQueue<List<Vulnerability>> VULNERABILITIES = new LinkedBlockingQueue<>()

  @CompileStatic
  void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
    super.configurePreAgent()
  }

  protected Closure getRequestEndAction() {
    { RequestContext requestContext, IGSpanInfo igSpanInfo ->
      // request end action
      IastRequestContext iastRequestContext = requestContext.getData(RequestContextSlot.IAST)
      if (iastRequestContext) {
        TaintedObjects taintedObjects = iastRequestContext.getTaintedObjects()
        TAINTED_OBJECTS.offer(new TaintedObjectCollection(taintedObjects))
        List<Vulnerability> vulns = iastRequestContext.getVulnerabilityBatch().getVulnerabilities() ?: []
        VULNERABILITIES.offer(vulns)
      }
    }
  }

  def setupSpec() {
    TaintableVisitor.DEBUG = true
  }

  void setup() {
    iastSystemSetup(requestEndAction)
  }

  void cleanup() {
    iastSystemCleanup()
  }

  protected TaintedObjectCollection getFinReqTaintedObjects() {
    TAINTED_OBJECTS.poll(15, TimeUnit.SECONDS)
  }

  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return null
  }

  protected void hasVulnerability(final Closure<Boolean> matcher) {
    List<Vulnerability> vulns = VULNERABILITIES.poll(15, TimeUnit.SECONDS)
    if(vulns.find(matcher) == null){
      throw new AssertionError("No matching vulnerability found. Vulnerabilities found: ${new JsonBuilder(vulns).toPrettyString()}")
    }
  }
}
