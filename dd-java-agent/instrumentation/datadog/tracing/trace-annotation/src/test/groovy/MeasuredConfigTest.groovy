import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.config.inversion.ConfigHelper
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.util.concurrent.Callable

class MeasuredConfigTest extends InstrumentationSpecification {
  // Opt out of strict config validation because dd.trace.methods uses class names
  // which generate dynamic config keys like DD_TRACE_TRACE_CONFIG_PACKAGE_CLASSNAME_ENABLED
  void setupSpec() {
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.methods", "package.ClassName[method1,method2];${ConfigTracedCallable.name}[call];${ConfigTracedCallable2.name}[*];")
    injectSysConfig("dd.measure.methods", "package.ClassName[method1,method2];${ConfigTracedCallable.name}[call];${ConfigTracedCallable2.name}[*];")
  }

  class ConfigTracedCallable implements Callable<String> {
    @Override
    String call() throws Exception {
      return "Hello!"
    }
  }
  class ConfigTracedCallable2 implements Callable<String> {
    int g

    ConfigTracedCallable2(){
      g = 4
    }
    @Override
    String call() throws Exception {
      return call_helper()
    }

    String call_helper() throws Exception {
      return "Hello2!"
    }

    String getValue() {
      return "hello"
    }
    void setValue(int value) {
      g = value
    }
  }

  def "test configuration based trace"() {
    when:
    Thread.sleep(100)
    new ConfigTracedCallable().call() == "Hello!"
    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ConfigTracedCallable.call"
          operationName "trace.annotation"
          measured true
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }
  def "test wildcard configuration"() {
    when:
    ConfigTracedCallable2 object = new ConfigTracedCallable2()
    object.call()
    object.hashCode()
    object == new ConfigTracedCallable2()
    object.toString()
    object.finalize()
    object.getValue()
    object.setValue(5)


    then:
    assertTraces(1) {
      trace(2) {
        span {
          resourceName "ConfigTracedCallable2.call"
          operationName "trace.annotation"
          measured true
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          resourceName "ConfigTracedCallable2.call_helper"
          operationName "trace.annotation"
          measured true
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }
}

