package datadog.trace.core.scopemanager

import datadog.trace.api.config.TracerConfig
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.util.test.DDSpecification

import static datadog.trace.agent.test.utils.ConfigUtils.withConfigOverride

class ScopeManagerDepthTest extends DDSpecification {
  def "scopemanager returns noop scope if depth exceeded"() {
    given:
    def tracer = CoreTracer.builder().writer(new ListWriter()).build()
    def scopeManager = tracer.scopeManager

    when: "fill up the scope stack"
    AgentScope scope = null
    for (int i = 0; i < depth; i++) {
      def span = tracer.buildSpan("test").start()
      scope = tracer.activateSpan(span)
      assert scope instanceof ContinuableScopeManager.ContinuableScope
    }

    then: "last scope is still valid"
    scopeManager.scopeStack().depth() == depth

    when: "activate span over limit"
    def span = tracer.buildSpan("test").start()
    scope = tracer.activateSpan(span)

    then: "a noop instance is returned"
    scope instanceof NoopAgentScope

    when: "activate a noop scope over the limit"
    scope = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.MANUAL)

    then: "still have a noop instance"
    scope instanceof NoopAgentScope

    and: "scope stack not effected."
    scopeManager.scopeStack().depth() == depth

    cleanup:
    scopeManager.scopeStack().clear()

    where:
    depth = 100  // Using ConfigDefaults here causes classloading issues
  }

  def "scopemanager ignores depth limit when 0"() {
    given:
    def tracer
    withConfigOverride(TracerConfig.SCOPE_DEPTH_LIMIT, "0") {
      tracer = CoreTracer.builder().writer(new ListWriter()).build()
    }
    def scopeManager = tracer.scopeManager

    when: "fill up the scope stack"
    AgentScope scope = null
    for (int i = 0; i < defaultLimit; i++) {
      def span = tracer.buildSpan("test").start()
      scope = tracer.activateSpan(span)
      assert scope instanceof ContinuableScopeManager.ContinuableScope
    }

    then: "last scope is still valid"
    scopeManager.scopeStack().depth() == defaultLimit

    when: "activate a scope"
    def span = tracer.buildSpan("test").start()
    scope = tracer.activateSpan(span)

    then: "a real scope is returned"
    !(scope instanceof NoopAgentScope)
    scopeManager.scopeStack().depth() == defaultLimit + 1

    when: "activate a noop span"
    scope = scopeManager.activate(NoopAgentSpan.INSTANCE, ScopeSource.MANUAL)

    then: "a real instance is still returned"
    !(scope instanceof NoopAgentScope)

    and: "scope stack not effected."
    scopeManager.scopeStack().depth() == defaultLimit + 2

    cleanup:
    scopeManager.scopeStack().clear()

    where:
    defaultLimit = 100 // Using ConfigDefaults here causes classloading issues
  }
}
