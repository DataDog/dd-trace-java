package com.datadog.iast

import datadog.trace.api.function.BiFunction
import datadog.trace.api.function.Supplier
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContext
import datadog.trace.test.util.DDSpecification
import spock.lang.Ignore

class IastSystemTest extends DDSpecification {

  void 'start'() {
    given:
    final ig = Spy(InstrumentationGateway)

    when:
    IastSystem.start(ig)

    then:
    2 * ig.registerCallback(_, _)

    when:
    final ctx = ig.getCallback(Events.get().requestStarted()).get().getResult()
    ig.getCallback(Events.get().requestEnded()).apply(ctx, Mock(IGSpanInfo))

    then:
    noExceptionThrown()
  }

  void 'start disabled'() {
    setup:
    injectSysConfig('dd.iast.enabled', "false")
    rebuildConfig()
    final ig = Mock(InstrumentationGateway)

    when:
    IastSystem.start(ig)

    then:
    0 * _
  }

  @Ignore("TODO")
  void 'start with previous callbacks'() {
    given:
    final Supplier<Flow<RequestContext<Object>>> reqStart = {
      return null
    }
    final BiFunction<RequestContext<Object>, IGSpanInfo, Flow<Void>> reqEnd = {
      return null
    }
    final ig = Spy(InstrumentationGateway)
    ig.registerCallback(Events.get().requestStarted(), reqStart)
    ig.registerCallback(Events.get().requestEnded(), reqEnd)

    when:
    IastSystem.start(ig)

    then:
    2 * ig.registerCallback(_, _)

    when:
    final ctx = ig.getCallback(Events.get().requestStarted()).get().getResult()
    ig.getCallback(Events.get().requestEnded()).apply(ctx, Mock(IGSpanInfo))

    then:
    noExceptionThrown()
  }
}
