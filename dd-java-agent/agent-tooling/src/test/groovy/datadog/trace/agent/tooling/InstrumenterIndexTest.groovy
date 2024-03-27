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
    index.instrumentationCount() == 2
    index.transformationCount() == 6

    index.instrumentationId(unknownInstrumentation) == -1
    index.transformationId(unknownTransformation) == -1

    def moduleIterator = index.modules().iterator()

    moduleIterator.hasNext()

    // multi-module declares several transformations
    def multiModule = moduleIterator.next()
    index.instrumentationId(multiModule) == 0

    def multiItr = multiModule.typeInstrumentations().iterator()
    index.transformationId(unknownTransformation) == -1
    index.transformationId(multiItr.next()) == 0
    index.transformationId(multiItr.next()) == 1
    index.transformationId(unknownTransformation) == -1
    index.transformationId(multiItr.next()) == 2
    index.transformationId(unknownTransformation) == -1
    index.transformationId(multiItr.next()) == 3
    index.transformationId(multiItr.next()) == 4
    index.transformationId(unknownTransformation) == -1
    !multiItr.hasNext()

    index.instrumentationId(unknownInstrumentation) == -1
    index.transformationId(unknownTransformation) == -1

    moduleIterator.hasNext()

    // self-module just declares itself as a transformation
    def selfModule = moduleIterator.next()
    index.instrumentationId(selfModule) == 1

    def selfItr = selfModule.typeInstrumentations().iterator()
    index.transformationId(selfItr.next()) == 5
    !selfItr.hasNext()

    !moduleIterator.hasNext()

    index.instrumentationId(unknownInstrumentation) == -1
    index.transformationId(unknownTransformation) == -1
  }
}
