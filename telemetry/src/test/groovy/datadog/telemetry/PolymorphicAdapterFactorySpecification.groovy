package datadog.telemetry

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import groovy.transform.CompileStatic
import spock.lang.Specification

class PolymorphicAdapterFactorySpecification extends Specification {

  @CompileStatic
  static class Container {
    Base base = new SubClass()
  }

  @CompileStatic
  static class Base {
    String baseField = 'foo'
  }

  @CompileStatic
  static class SubClass extends Base {
    String subClassField = 'bar'
  }

  void 'default adapter does not serialize subclass fields'() {
    setup:
    JsonAdapter<Container> adapter = new Moshi.Builder().build().adapter(Container)

    expect:
    adapter.toJson(new Container()) == '{"base":{"baseField":"foo"}}'
  }


  void 'polymorphic adapter does serialize subclass fields'() {
    setup:
    JsonAdapter<Container> adapter = new Moshi.Builder()
      .add(new PolymorphicAdapterFactory(Base))
      .build().adapter(Container)

    expect:
    adapter.toJson(new Container()) ==
      '{"base":{"baseField":"foo","subClassField":"bar"}}'
  }
}
