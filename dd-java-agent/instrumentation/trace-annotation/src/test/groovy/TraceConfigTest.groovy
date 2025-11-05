import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.util.concurrent.Callable

class TraceConfigTest extends InstrumentationSpecification {

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
}
