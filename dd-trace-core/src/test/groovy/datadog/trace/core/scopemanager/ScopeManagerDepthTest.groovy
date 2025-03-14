package datadog.trace.core.scopemanager

import datadog.trace.api.config.TracerConfig
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan

class ScopeManagerDepthTest extends DDCoreSpecification {
  def "scopemanager returns noop scope if depth exceeded"() {
    given:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def scopeManager = tracer.scopeManager

    when: "fill up the scope stack"
    AgentScope scope
    for (int i = 0; i < depth; i++) {
      def testSpan = tracer.buildSpan("test", "test").start()
      scope = tracer.activateSpan(testSpan)
      assert scope instanceof ContinuableScope
    }

    then: "last scope is still valid"
    scopeManager.scopeStack().depth() == depth

    when: "activate span over limit"
    def span = tracer.buildSpan("test", "test").start()
    scope = tracer.activateSpan(span)

    then: "a noop instance is returned"
    scope == noopScope()

    when: "activate a noop scope over the limit"
    scope = scopeManager.activateManualSpan(noopSpan())

    then: "still have a noop instance"
    scope == noopScope()

    and: "scope stack not effected."
    scopeManager.scopeStack().depth() == depth

    cleanup:
    scopeManager.scopeStack().clear()
    tracer.close()

    where:
    depth = 100  // Using ConfigDefaults here causes classloading issues
  }

  def "scopemanager ignores depth limit when 0"() {
    given:
    injectSysConfig(TracerConfig.SCOPE_DEPTH_LIMIT, "0")
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def scopeManager = tracer.scopeManager

    when: "fill up the scope stack"
    AgentScope scope
    for (int i = 0; i < defaultLimit; i++) {
      def testSpan = tracer.buildSpan("test", "test").start()
      scope = tracer.activateSpan(testSpan)
      assert scope instanceof ContinuableScope
    }

    then: "last scope is still valid"
    scopeManager.scopeStack().depth() == defaultLimit

    when: "activate a scope"
    def span = tracer.buildSpan("test", "test").start()
    scope = tracer.activateSpan(span)

    then: "a real scope is returned"
    scope != noopScope()
    scopeManager.scopeStack().depth() == defaultLimit + 1

    when: "activate a noop span"
    scope = scopeManager.activateManualSpan(noopSpan())

    then: "a real instance is still returned"
    scope != noopScope()

    and: "scope stack not effected."
    scopeManager.scopeStack().depth() == defaultLimit + 2

    cleanup:
    scopeManager.scopeStack().clear()
    tracer.close()

    where:
    defaultLimit = 100 // Using ConfigDefaults here causes classloading issues
  }

  def "depth is correctly updated with out of order closing"() {
    // The decision here is that depth is the top-most open scope
    // Closed scopes that are not on top still count for depth

    given:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def scopeManager = tracer.scopeManager

    when:
    AgentSpan firstSpan = tracer.buildSpan("test", "foo").start()
    AgentScope firstScope = tracer.activateSpan(firstSpan)

    AgentSpan secondSpan = tracer.buildSpan("test", "foo").start()
    AgentScope secondScope = tracer.activateSpan(secondSpan)

    then:
    scopeManager.scopeStack().depth() == 2

    when:
    firstSpan.finish()
    firstScope.close()

    then:
    scopeManager.scopeStack().depth() == 2

    when:
    secondSpan.finish()
    secondScope.close()

    then:
    scopeManager.scopeStack().depth() == 0

    cleanup:
    tracer.close()
  }
}
