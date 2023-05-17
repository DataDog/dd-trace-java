package datadog.trace.instrumentation.ktor

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator
import datadog.trace.instrumentation.ktor.KtorDecorator.DECORATE
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.BaseApplicationPlugin
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.Routing
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase

// TODO rewrite it in Java with using mangled names
class KtorServerTracing {

  companion object Plugin : BaseApplicationPlugin<Application, Configuration, KtorServerTracing> {
    @JvmStatic
    fun instrument(application: Application) {
      println(">>> Application initialization: $application")

      application.install(KtorServerTracing)
    }

    // TODO get these constant from the Decorator
    private val contextKey = AttributeKey<AgentSpan>("Datadog")
    private val errorKey = AttributeKey<Throwable>("DatadogException")

    override val key: AttributeKey<KtorServerTracing> = AttributeKey("Datadog")

    // TODO try to instrument: io.ktor.util.pipeline.Pipeline.execute instead of using this type of late init
    @Volatile
    private var monitorInitialized = false

    fun lateMonitorSubscribe(pipeline: Application) {
      if (!monitorInitialized) {
        monitorInitialized = true
        pipeline.environment.monitor.subscribe(Routing.RoutingCallStarted) { call ->
          val context = call.attributes.getOrNull(contextKey)
          if (context != null) {
            val span = call.attributes.getOrNull(contextKey)
            if (span != null) {
              HttpResourceDecorator.HTTP_RESOURCE_DECORATOR.withRoute(
                span,
                DECORATE.method(call.request),
                call.route.parent.toString()
              )
            }
          }
        }
      }
    }

    override fun install(pipeline: Application, configure: Configuration.() -> Unit): KtorServerTracing {
      val plugin = KtorServerTracing()

      val startPhase = PipelinePhase("Datadog")
      pipeline.insertPhaseBefore(ApplicationCallPipeline.Monitoring, startPhase)
      pipeline.intercept(startPhase) {
        lateMonitorSubscribe(application)

        // TODO is it possible to get call.attributes to get DD_SPAN_ATTRIBUTE to access underlying netty/jetty/tomcat span?
        println(">>> call.attributes: " + call.attributes.allKeys.toList().toString())

        val activeSpan = activeSpan()
        val span = if (activeSpan != null) {
          // Here we assume if there is an active span it's a netty/jetty/tomcat span, so we just redecorate it.
          activeSpan.setOperationName(DECORATE.operationName())
          activeSpan
        } else {
          // Otherwise, create a new span with an extracted context, e.g. when Ktor is used with Coroutine I/O Engine (CIO)
          // TODO Maybe use existing `call.attributes: [AttributeKey: CallStartTime, AttributeKey: EngineResponse]` for proper start time and whatever EngineResponse contains?
          val context = DECORATE.extract(call)
          val startSpan = DECORATE.startSpan(call, context)
          startSpan.setMeasured(true)
          startSpan
        }

        DECORATE.afterStart(span)
        val scope = activateSpan(span)
        call.attributes.put(contextKey, span)
        scope.use {
          try {
            proceed()
          } catch (err: Throwable) {
            call.attributes.put(errorKey, err)
            // TODO maybe stashing an error is not necessary and we could just do `scope.span().addThrowable(err)`
            throw err
          }
        }
      }

      val postSendPhase = PipelinePhase("DatadogPostSend")
      pipeline.sendPipeline.insertPhaseAfter(ApplicationSendPipeline.After, postSendPhase)
      pipeline.sendPipeline.intercept(postSendPhase) {
        val span = call.attributes.getOrNull(contextKey)
        if (span != null) {
          var error: Throwable? = call.attributes.getOrNull(errorKey)
          try {
            proceed()
          } catch (t: Throwable) {
            error = t
            throw t
          } finally {
            DECORATE.onError(span, error)
            DECORATE.beforeFinish(span)
            span.finish()
          }
        } else {
          proceed()
        }
      }

      return plugin
    }
  }

  class Configuration
}
