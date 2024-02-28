import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.SpringApplication

class SpringBootApplicationTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.integration.spring-boot.enabled", "true")
  }
  static class BeanWhoTraces implements InitializingBean {

    @Override
    void afterPropertiesSet() throws Exception {
      runUnderTrace("spring", {})
    }
  }

  def 'should change service name before bean factory initializes'() {
    setup:
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
    "application-name-from-args"          | new String[]{
      "--spring.application.name=application-name-from-args"
    }
    "application-name-from-properties"         | new String[0] // will load from properties
  }
}

class SpringBootApplicationNotAppliedForkedTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("service", "myservice")
    injectSysConfig("trace.integration.spring-boot.enabled", "true")
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
