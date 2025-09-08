import datadog.trace.api.ProcessTags

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.SpringApplication

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED

class SpringBootApplicationTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
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
    and:
    def processTags = ProcessTags.getTagsForSerialization()
    assert processTags.toString() =~ ".+,springboot.application:$expectedService,springboot.profile:$expectedProfile"
    context != null
    cleanup:
    context?.stop()
    where:
    expectedService                    | expectedProfile | args
    "application-name-from-args"       | "prod"          | new String[]{
      "--spring.application.name=application-name-from-args",
      "--spring.profiles.active=prod,common",
    }
    "application-name-from-properties" | "default"       | new String[0] // will load from properties
  }
}

class SpringBootApplicationNotAppliedForkedTest extends InstrumentationSpecification {
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
