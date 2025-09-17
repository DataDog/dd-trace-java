import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper
import io.grpc.Context

class UnwrapGrpcContextForkedTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.profiling.enabled", "true")
  }

  def "test unwrap gRPC context wrappers"() {
    // relies on TaskUnwrappingInstrumentation (java-concurrent) targeting grpc context
    setup:
    def runnable = {}
    def callable = {"result"}
    when:
    def unwrappedRunnableType = TaskWrapper.getUnwrappedType(Context.ROOT.wrap(runnable))
    then:
    unwrappedRunnableType == runnable.getClass()
    when:
    def unwrappedCallableType = TaskWrapper.getUnwrappedType(Context.ROOT.wrap(callable))
    then:
    unwrappedCallableType == callable.getClass()
  }
}
