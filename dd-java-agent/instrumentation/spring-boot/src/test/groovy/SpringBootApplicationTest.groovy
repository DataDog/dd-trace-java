import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.AgentTestRunner
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
    setup:
    if (userService != null) {
      injectSysConfig("service.name", userService)
    }
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
    userService   | expectedService | args
    null          | "args"          | new String[]{
      "--spring.application.name=args"
    }
    "my-service"  | "my-service"    | new String[]{
      "--spring.application.name=args"
    }
    "my-service"  | "my-service"    | new String[0]
    null          | "props"         | new String[0] // will load from properties
  }
}
