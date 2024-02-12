import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.SpringApplication
import spock.lang.Unroll

class SpringBootApplicationTest extends AgentTestRunner {

  static class BeanWhoTraces implements InitializingBean {

    @Override
    void afterPropertiesSet() throws Exception {
      runUnderTrace("spring", {})
    }
  }

  @Unroll
  def 'should change service name before bean factory initializes'() {
    def context = SpringApplication.run(BeanWhoTraces, args)
    when:
    context.start()
    then:
    assertTraces(1, {
      trace(1) {
        span {
          serviceName expectedService
          operationName "spring"
        }
      }
    })
    context != null
    cleanup:
    context?.stop()
    where:
    expectedService | args
    "args"          | new String[]{
      "--spring.application.name=args"
    }
    "props"         | new String[0] // will load from properties
  }
}

class SpringBootApplicationNotAppliedForkedTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("service", "myservice")
  }

  def 'should not service name when user inferred dd_service'() {
    def context = SpringApplication.run(SpringBootApplicationTest.BeanWhoTraces, new String[0])
    when:
    context.start()
    then:
    assertTraces(1, {
      trace(1) {
        span {
          serviceName "myservice"
          operationName "spring"
        }
      }
    })
    context != null
    cleanup:
    context?.stop()
  }
}
