package datadog.trace.instrumentation.ognl

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import ognl.Ognl

class OgnlInstrumentationSpec extends InstrumentationSpecification {
  void 'creates a new span for ognl parsing expressions'() {
    when:
    AgentSpan span = AgentTracer.get().buildSpan("top-span").start()
    def ognlExpr
    AgentTracer.activateSpan(span).withCloseable {
      ognlExpr = Ognl.parseExpression('foo')
    }
    span.finish()

    then:
    Ognl.getValue(ognlExpr, [foo: 42]) == 42
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        it.span {
          operationName 'top-span'
        }
        it.span {
          operationName 'ognl.parse'
          tags {
            'ognl.expression' 'foo'
            assertedTags << 'thread.name'
            assertedTags << 'thread.id'
          }
        }
      }
    }
  }

  void 'no span without previous active span'() {
    when:
    Ognl.parseExpression('foo')

    then:
    assertTraces(0) {}
  }
}
