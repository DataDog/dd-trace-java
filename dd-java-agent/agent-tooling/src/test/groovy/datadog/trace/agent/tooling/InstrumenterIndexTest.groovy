package datadog.trace.agent.tooling

import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class InstrumenterIndexTest extends DDSpecification {

  @Shared
  def unknownInstrumentation = new InstrumenterModule('unknown') {}

  @Shared
  def unknownTransformation = new Instrumenter() {}

  def "index keeps track of instrumentation and transformation ids"() {
    when:
    InstrumenterIndex index = InstrumenterIndex.buildIndex()

    then:
    index.instrumentationCount() == 4
    index.transformationCount() == 8

    index.instrumentationId(unknownInstrumentation) == -1
    index.transformationId(unknownTransformation) == -1

    def moduleIterator = index.modules(InstrumenterModuleFilter.ALL_MODULES).iterator()

    moduleIterator.hasNext()

    // module with order=-100 is applied first
    def firstModule = moduleIterator.next()
    firstModule.class.simpleName == 'TestIndexFirstModule'
    firstModule.order() == -100
    index.instrumentationId(firstModule) == 0

    moduleIterator.hasNext()

    // multi-module declares several transformations
    def multiModule = moduleIterator.next()
    multiModule.class.simpleName == 'TestIndexMultiModule'
    index.instrumentationId(multiModule) == 1

    def multiItr = multiModule.typeInstrumentations().iterator()
    index.transformationId(unknownTransformation) == -1
    index.transformationId(multiItr.next()) == 1
    index.transformationId(multiItr.next()) == 2
    index.transformationId(unknownTransformation) == -1
    index.transformationId(multiItr.next()) == 3
    index.transformationId(unknownTransformation) == -1
    index.transformationId(multiItr.next()) == 4
    index.transformationId(multiItr.next()) == 5
    index.transformationId(unknownTransformation) == -1
    !multiItr.hasNext()

    index.instrumentationId(unknownInstrumentation) == -1
    index.transformationId(unknownTransformation) == -1

    moduleIterator.hasNext()

    // self-module just declares itself as a transformation
    def selfModule = moduleIterator.next()
    selfModule.class.simpleName == 'TestIndexSelfModule'
    index.instrumentationId(selfModule) == 2

    def selfItr = selfModule.typeInstrumentations().iterator()
    index.transformationId(selfItr.next()) == 6
    !selfItr.hasNext()

    moduleIterator.hasNext()

    // module with order=100 is applied last
    def lastModule = moduleIterator.next()
    lastModule.class.simpleName == 'TestIndexLastModule'
    lastModule.order() == 100
    index.instrumentationId(lastModule) == 3

    !moduleIterator.hasNext()

    index.instrumentationId(unknownInstrumentation) == -1
    index.transformationId(unknownTransformation) == -1
  }
}
