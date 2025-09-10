package datadog.trace.instrumentation.java.util

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.WeakRandomnessModule
import foo.bar.TestRandomSuite

import java.security.SecureRandom

class RandomCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test #target.#method on #target'() {
    setup:
    final module = Mock(WeakRandomnessModule)
    InstrumentationBridge.registerIastModule(module)
    final suite = new TestRandomSuite(target.newInstance())

    when:
    suite.&"$method".call(args as Object[])

    then:
    1 * module.onWeakRandom(target)

    where:
    target            | method         | args
    SecureRandom      | 'nextBoolean'  | []
    Random            | 'nextBoolean'  | []
    Random            | 'nextInt'      | []
    Random            | 'nextInt'      | [3]
    Random            | 'nextLong'     | []
    Random            | 'nextFloat'    | []
    Random            | 'nextDouble'   | []
    Random            | 'nextGaussian' | []
    Random            | 'nextBytes'    | [[1, 2, 3] as byte[]]
    Random            | 'ints'         | []
    Random            | 'ints'         | [0, 10]
    Random            | 'ints'         | [10L]
    Random            | 'ints'         | [10L, 0, 10]
    Random            | 'doubles'      | []
    Random            | 'doubles'      | [0D, 10D]
    Random            | 'doubles'      | [10L]
    Random            | 'doubles'      | [10L, 0D, 10D]
    Random            | 'longs'        | []
    Random            | 'longs'        | [0L, 10L]
    Random            | 'longs'        | [10L]
    Random            | 'longs'        | [10L, 0L, 10L]
  }
}
