import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.graphqljava.GraphQLInstrumentation
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.SimpleInstrumentation

class GraphQLInstallationTest extends AgentTestRunner {
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
}
