package com.datadog.iast.test

import com.datadog.iast.IastRequestContext
import com.datadog.iast.IastSystem
import com.datadog.iast.model.Range
import com.datadog.iast.taint.TaintedObject
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.EventType
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import java.util.function.BiFunction
import java.util.function.Supplier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

trait IastRequestContextPreparationTrait {

  static void iastSystemSetup(Closure reqEndAction = null) {
    final tracer = AgentTracer.get()
    def ss = tracer.getSubscriptionService(RequestContextSlot.IAST)
    IastSystem.start(ss, new NoopOverheadController())

    EventType<Supplier<Flow<Object>>> requestStarted = Events.get().requestStarted()
    EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> requestEnded =
      Events.get().requestEnded()

    // get original callbacks
    CallbackProvider provider = tracer.getCallbackProvider(RequestContextSlot.IAST)
    def origRequestStarted = provider.getCallback(requestStarted)
    def origRequestEnded = provider.getCallback(requestEnded)

    // wrap the original IG callbacks
    ss.reset()
    ss.registerCallback(requestStarted, new TaintedMapSaveStrongRefsRequestStarted(orig: origRequestStarted))
    ss.registerCallback(
      requestEnded,
      reqEndAction == null
      ? origRequestEnded
      : new TaintedMapSavingRequestEnded(original: origRequestEnded, beforeAction: reqEndAction))
  }

  static void iastSystemCleanup() {
    AgentTracer.get().getSubscriptionService(RequestContextSlot.IAST).reset()
    InstrumentationBridge.clearIastModules()
  }

  static class TaintedMapSaveStrongRefsRequestStarted implements Supplier<Flow<Object>> {
    Supplier<Flow<Object>> orig

    @Override
    Flow<Object> get() {
      IastRequestContext reqCtx = orig.get().result
      def taintedObjectWrapper = new TaintedObjectSaveStrongReference(delegate: reqCtx.taintedObjects)
      new Flow.ResultFlow(new IastRequestContext(taintedObjectWrapper))
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

      private final static Logger LOGGER = withLogger("map tainted objects")

      private static Logger withLogger(final String name) {
        final logger = LoggerFactory.getLogger(name)

        // Check logger class by name to avoid NoClassDefFoundError at runtime for tests without Logback.
        if (logger.class.name == "ch.qos.logback.classic.Logger") {
          ((ch.qos.logback.classic.Logger) logger).level = ch.qos.logback.classic.Level.DEBUG
        }
        return logger
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
