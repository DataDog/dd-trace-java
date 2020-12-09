import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.trace_annotation.TraceConfigInstrumentation

import java.util.concurrent.Callable

class TraceConfigTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.methods", "package.ClassName[method1,method2];${ConfigTracedCallable.name}[call]")
  }

  class ConfigTracedCallable implements Callable<String> {
    @Override
    String call() throws Exception {
      return "Hello!"
    }
  }

  def "test configuration based trace"() {
    expect:
    new ConfigTracedCallable().call() == "Hello!"

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ConfigTracedCallable.call"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test configuration #value"() {
    setup:
    if (value) {
      injectSysConfig("dd.trace.methods", value)
    } else {
      removeSysConfig("dd.trace.methods")
    }

    expect:
    new TraceConfigInstrumentation().classMethodsToTrace == expected

    where:
    value                                                           | expected
    null                                                            | [:]
    " "                                                             | [:]
    "some.package.ClassName"                                        | [:]
    "some.package.ClassName[ , ]"                                   | [:]
    "some.package.ClassName[ , method]"                             | [:]
    "some.package.Class\$Name[ method , ]"                          | ["some.package.Class\$Name": ["method"].toSet()]
    "ClassName[ method1,]"                                          | ["ClassName": ["method1"].toSet()]
    "ClassName[method1 , method2]"                                  | ["ClassName": ["method1", "method2"].toSet()]
    "Class\$1[method1 ] ; Class\$2[ method2];"                      | ["Class\$1": ["method1"].toSet(), "Class\$2": ["method2"].toSet()]
    "Duplicate[method1] ; Duplicate[method2]  ;Duplicate[method3];" | ["Duplicate": ["method3"].toSet()]
  }
}
