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

  class ConfigTracedCallable2 implements Callable<String> {
    @Override
    String call() throws Exception {
      return call_helper();
    }

    String call_helper() throws Exception {
      return "Hello2!";
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

  def "test configuration based trace with wildcards"() {
    setup:
    injectSysConfig("dd.trace.methods", "ConfigTracedCallable2[*]")

    expect:
    new ConfigTracedCallable2().call() == "Hello2!"

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    assertTraces(1) {
      trace(2) {
        span {
          resourceName "ConfigTracedCallable2.call"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          resourceName "ConfigTracedCallable2.call_helper"
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
    "ClassName[*]"                                                  | ["ClassName": ["*"].toSet()]
    "ClassName[*,asdfg]"                                            | [:]
    "ClassName[asdfg,*]"                                            | [:]
  }
}
