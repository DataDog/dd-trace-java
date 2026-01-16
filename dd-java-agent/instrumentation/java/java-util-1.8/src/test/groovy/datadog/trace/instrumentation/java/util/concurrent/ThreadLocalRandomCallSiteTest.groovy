package datadog.trace.instrumentation.java.util.concurrent

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.WeakRandomnessModule
import foo.bar.TestThreadLocalRandomSuite

import java.util.concurrent.ThreadLocalRandom

class ThreadLocalRandomCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test #target.class.name #method methods'() {
    setup:
    final module = Mock(WeakRandomnessModule)
    InstrumentationBridge.registerIastModule(module)
    final suite = new TestThreadLocalRandomSuite(target)

    when:
    suite.&"$method".call(args as Object[])

    then:
    1 * module.onWeakRandom(target.class)

    where:
    target                      | method         | args
    ThreadLocalRandom.current() | 'nextBoolean'  | []
    ThreadLocalRandom.current() | 'nextInt'      | []
    ThreadLocalRandom.current() | 'nextInt'      | [3]
    ThreadLocalRandom.current() | 'nextLong'     | []
    ThreadLocalRandom.current() | 'nextFloat'    | []
    ThreadLocalRandom.current() | 'nextDouble'   | []
    ThreadLocalRandom.current() | 'nextGaussian' | []
    ThreadLocalRandom.current() | 'nextBytes'    | [[1, 2, 3] as byte[]]
    ThreadLocalRandom.current() | 'ints'         | []
    ThreadLocalRandom.current() | 'ints'         | [0, 10]
    ThreadLocalRandom.current() | 'ints'         | [10L]
    ThreadLocalRandom.current() | 'ints'         | [10L, 0, 10]
    ThreadLocalRandom.current() | 'doubles'      | []
    ThreadLocalRandom.current() | 'doubles'      | [0D, 10D]
    ThreadLocalRandom.current() | 'doubles'      | [10L]
    ThreadLocalRandom.current() | 'doubles'      | [10L, 0D, 10D]
    ThreadLocalRandom.current() | 'longs'        | []
    ThreadLocalRandom.current() | 'longs'        | [0L, 10L]
    ThreadLocalRandom.current() | 'longs'        | [10L]
    ThreadLocalRandom.current() | 'longs'        | [10L, 0L, 10L]
  }
}
