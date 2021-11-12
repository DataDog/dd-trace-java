import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.trace_annotation.TraceConfigInstrumentation

import java.util.concurrent.Callable

class TraceConfigTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.methods", "package.ClassName[method1,method2];${ConfigTracedCallable.name}[call];${ConfigTracedCallable2.name}[*];${Animal.name}[animalSound];${DictionaryElement.name}[*];${Floor.name}[setNumber];${Mammal.name}[*]")
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

  interface Mammal {
    void name(String newName)
    void height(int newHeight)
  }

  interface Floor {
    void setNumber()
  }

  class Fifth implements Floor {
    int floorNumber

    void setNumber() {
      floorNumber = 5
    }
  }

  class Human implements Mammal {
    String name
    String height

    void name(String newName){
      name = newName
    }
    void height(int newHeight){
      height = newHeight
    }
  }


  abstract class Animal {
    abstract void animalSound()
    void sleep() {
      System.out.println("Zzz")
    }
  }

  abstract class DictionaryElement{
    static {
      System.out.println("Static initializer (should not be included in wildcard)")
    }
    abstract void produceDefinition()
  }

  class Sophisticated extends DictionaryElement{
    void produceDefinition() {
      System.out.println("of such excellence, grandeur, or beauty as to inspire great admiration or awe.")
    }
  }
  class Pig extends Animal {
    void animalSound() {
      System.out.println("The pig says: wee wee")
    }
  }

  def "test configuration based trace"() {
    when:
    new ConfigTracedCallable().call() == "Hello!"
    new Pig().animalSound()
    then:
    assertTraces(2) {
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
      trace(1) {
        span {
          resourceName "Pig.animalSound"
          operationName "trace.annotation"
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
    new Sophisticated().produceDefinition()


    then:
    assertTraces(2) {
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
      trace(1) {
        span {
          resourceName "Sophisticated.produceDefinition"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test wildcard configuration with class implementing interface"() {

    when:
    Human charlie = new Human()
    charlie.name("Charlie")
    charlie.height(4)

    then:
    assertTraces(2) {
      trace(1) {
        span {
          resourceName "Human.name"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          resourceName "Human.height"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }
  def "test wildcard configuration based on class extending abstract class"() {

    when:
    new Pig().animalSound()
    new Fifth().setNumber()
    then:
    assertTraces(2) {
      trace(1) {
        span {
          resourceName "Pig.animalSound"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          resourceName "Fifth.setNumber"
          operationName "trace.annotation"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "test configuration \"#value\""() {
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
    "*"                                                             | [:]
    "some.package.ClassName"                                        | [:]
    "some.package.ClassName[ , ]"                                   | [:]
    "some.package.ClassName[ , method]"                             | [:]
    "some.package.ClassName[ method1,  , method2]"                  | [:]
    "some.package.Class\$Name[ method , ]"                          | ["some.package.Class\$Name": ["method"].toSet()]
    "some.package.Class\$Name[ method , , ]"                        | [:]
    "ClassName[ method1,]"                                          | ["ClassName": ["method1"].toSet()]
    "ClassName[method1 , method2]"                                  | ["ClassName": ["method1", "method2"].toSet()]
    "Class\$1[method1 ] ; Class\$2[ method2];"                      | ["Class\$1": ["method1"].toSet(), "Class\$2": ["method2"].toSet()]
    "Duplicate[method1] ; Duplicate[method2]  ;Duplicate[method3];" | ["Duplicate": ["method1", "method2", "method3"].toSet()]
    "Duplicate[method1] ; Duplicate[method2]  ;Duplicate[*];"       | ["Duplicate": ["method1", "method2", "*"].toSet()]
    "ClassName[*]"                                                  | ["ClassName": ["*"].toSet()]
    "ClassName[asdfg*]"                                             | [:]
    "ClassName[*,asdfg]"                                            | [:]
    "ClassName[asdfg,*]"                                            | [:]
    "Class[*] ; Class\$2[ method2];"                                | ["Class": ["*"].toSet(), "Class\$2": ["method2"].toSet()]
    "Class[*] ; Class\$2[ method2];      "                          | ["Class": ["*"].toSet(), "Class\$2": ["method2"].toSet()]
    "     Class[*] ; Class\$2[ method2];      "                     | ["Class": ["*"].toSet(), "Class\$2": ["method2"].toSet()]
    "     Class[*] ; Class\$2[ method2];"                           | ["Class": ["*"].toSet(), "Class\$2": ["method2"].toSet()]
    "c1[m1,m2];c2[m1,m2]"                                           | ["c1": ["m1", "m2"].toSet(), "c2": ["m1", "m2"].toSet()]
    "c1;[m1,m2]"                                                    | [:]
    "c1[m1;m2]"                                                     | [:]
    "c1[m1],"                                                       | [:]
    "c1[m1];asdfg"                                                  | [:]
    "asdfg;c1[m1]"                                                  | [:]
  }

  def "survive very long list"() {
    setup:
    def mset = ["m1", "m2"].toSet()
    Map<String, Set<String>> expected = [:]
    def methods = (1..600).collect {
      expected.put("c$it".toString(), mset)
      return "c$it[${mset.join(",")}]"
    }.join(";")
    injectSysConfig("dd.trace.methods", methods)

    expect:
    new TraceConfigInstrumentation().classMethodsToTrace == expected
  }
}
