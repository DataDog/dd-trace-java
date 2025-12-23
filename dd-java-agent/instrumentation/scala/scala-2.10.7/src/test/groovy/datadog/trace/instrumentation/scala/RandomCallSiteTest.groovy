package datadog.trace.instrumentation.scala


import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.WeakRandomnessModule

class RandomCallSiteTest extends AbstractIastScalaTest {

  @Override
  String suiteName() {
    return 'foo.bar.TestRandomSuite'
  }

  void 'test scala.util.Random.#method'() {
    setup:
    final module = Mock(WeakRandomnessModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    testSuite.&"$method".call(args as Object[])

    then:
    1 * module.onWeakRandom(scala.util.Random)
    0 * _

    where:
    method              | args
    'nextBoolean'       | []
    'nextInt'           | []
    'nextInt'           | [3]
    'nextLong'          | []
    'nextFloat'         | []
    'nextDouble'        | []
    'nextGaussian'      | []
    'nextBytes'         | [[1, 2, 3] as byte[]]
    'nextString'        | [2]
    'nextPrintableChar' | []
  }
}
