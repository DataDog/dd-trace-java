package com.datadog.iast

import com.datadog.iast.HasDependencies.Dependencies
import com.datadog.iast.overhead.OverheadController
import com.datadog.iast.telemetry.IastTelemetry
import datadog.trace.api.Config
import datadog.trace.api.gateway.Flow
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.stacktrace.StackWalker
import groovy.transform.CompileDynamic

@CompileDynamic
class RequestStartedHandlerTest extends DDSpecification {

  void 'request starts successfully'() {
    given:
    final OverheadController overheadController = Mock(OverheadController)
    final iastCtx = Mock(IastRequestContext)
    final IastTelemetry telemetry = Mock(IastTelemetry)
    final StackWalker stackWalker = Mock(StackWalker)
    final dependencies = new Dependencies(
      Config.get(), new Reporter(), overheadController, telemetry, stackWalker
      )
    def handler = new RequestStartedHandler(dependencies)

    when:
    def flow = handler.get()

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() instanceof IastRequestContext
    1 * overheadController.acquireRequest() >> true
    1 * telemetry.onRequestStarted() >> iastCtx
    0 * _
  }

  void 'request start cannot acquire'() {
    given:
    final OverheadController overheadController = Mock(OverheadController)
    final IastTelemetry telemetry = Mock(IastTelemetry)
    final StackWalker stackWalker = Mock(StackWalker)
    final dependencies = new Dependencies(
      Config.get(), new Reporter(), overheadController, telemetry, stackWalker
      )
    def handler = new RequestStartedHandler(dependencies)

    when:
    def flow = handler.get()

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * overheadController.acquireRequest() >> false
    0 * _
  }
}
