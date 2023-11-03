package com.datadog.iast.test

import com.datadog.iast.IastSystem
import com.datadog.iast.model.Range
import com.datadog.iast.taint.TaintedObject
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.gateway.*
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.function.BiFunction
import java.util.function.Supplier

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.get

trait IastRequestContextPreparationTrait {

  void iastSystemSetup(Closure reqEndAction = null, TaintedObjects taintedObjects = null) {
    def ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.IAST)
    IastSystem.start(ss, new NoopOverheadController(), new TaintedObjectSaveStrongReference(delegate: taintedObjects))

    EventType<Supplier<Flow<Object>>> requestStarted = Events.get().requestStarted()
    EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> requestEnded =
      Events.get().requestEnded()

    // get original callbacks
    CallbackProvider provider = AgentTracer.get().getCallbackProvider(RequestContextSlot.IAST)
    def origRequestStarted = provider.getCallback(requestStarted)
    def origRequestEnded = provider.getCallback(requestEnded)

    // wrap the original IG callbacks
    ss.reset()
    ss.registerCallback(requestStarted, origRequestStarted)
    if (reqEndAction != null) {
      ss.registerCallback(requestEnded, new TaintedMapSavingRequestEnded(
        original: origRequestEnded, beforeAction: reqEndAction))
    }
  }

  static void iastSystemCleanup() {
    get().getSubscriptionService(RequestContextSlot.IAST).reset()
    InstrumentationBridge.clearIastModules()
  }

  static class TaintedObjectSaveStrongReference implements TaintedObjects {
    @Delegate
    TaintedObjects delegate

    List<Object> objects = Collections.synchronizedList([])

    @Override
    TaintedObject taint(Object obj, Range[] ranges) {
      objects << obj
      final tainted = this.delegate.taint(obj, ranges)
      logTaint obj
      return tainted
    }

    private final static Logger LOGGER = LoggerFactory.getLogger("map tainted objects")
    static {
      ((ch.qos.logback.classic.Logger) LOGGER).level = ch.qos.logback.classic.Level.DEBUG
    }

    private static void logTaint(Object o) {
      def content
      if (o.getClass().name.startsWith('java.')) {
        content = o
      } else {
        content = '(value not shown)' // toString() may trigger tainting
      }
      // Some Scala classes will produce a "Malformed class name" when calling Class#getSimpleName in JDK 8,
      // so be sure to call Class#getName instead.
      // See https://github.com/scala/bug/issues/2034
      LOGGER.debug("taint: {}[{}] {}",
        o.getClass().getName(), Integer.toHexString(System.identityHashCode(o)), content)
    }
  }

  static class TaintedMapSavingRequestEnded implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {
    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> original
    Closure beforeAction

    @Override
    Flow<Void> apply(RequestContext requestContext, IGSpanInfo igSpanInfo) {
      beforeAction.call(requestContext, igSpanInfo)
      original.apply(requestContext, igSpanInfo)
    }
  }
}
