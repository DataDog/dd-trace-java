import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.graphqljava14.GraphQLInstrumentation
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.SimpleInstrumentation

class GraphQLInstallationTest extends InstrumentationSpecification {
  static class TestInst extends SimpleInstrumentation {}

  def "install GraphQL instrumentation when there is no other instrumentation"() {
    when:
    def inst = GraphQLInstrumentation.install(null)

    then:
    inst != null
    inst.class == GraphQLInstrumentation

    when:
    // do not install if it's already been installed
    def inst2 = GraphQLInstrumentation.install(inst)

    then:
    inst2 == inst
  }

  def "add GraphQL instrumentation to the existing instrumentation"() {
    when:
    def testInst = new TestInst()
    def inst2 = GraphQLInstrumentation.install(testInst)

    then:
    inst2.class == ChainedInstrumentation
    def insts = inst2.getInstrumentations()
    insts.get(0) == testInst
    def inst = insts.get(1)
    inst.class == GraphQLInstrumentation

    when:
    // do not install if it's already been installed
    def inst3 = GraphQLInstrumentation.install(inst2)

    then:
    inst2 == inst3
  }

  def "add GraphQL instrumentation to the existing chained instrumentation"() {
    when:
    def testInst1 = new TestInst()
    def testInst2 = new TestInst()
    def inst3 = GraphQLInstrumentation.install(new ChainedInstrumentation([testInst1, testInst2]))

    then:
    inst3.class == ChainedInstrumentation
    def insts = inst3.getInstrumentations()
    insts.get(0) == testInst1
    insts.get(1) == testInst2
    def inst = insts.get(2)
    inst.class == GraphQLInstrumentation

    when:
    // do not install if it's already been installed
    def inst4 = GraphQLInstrumentation.install(inst3)

    then:
    inst3 == inst4
  }
}
