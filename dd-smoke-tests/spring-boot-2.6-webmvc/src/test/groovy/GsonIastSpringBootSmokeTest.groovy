import datadog.smoketest.AbstractGsonIastSpringBootSmokeTest

class GsonIastSpringBootSmokeTest extends  AbstractGsonIastSpringBootSmokeTest{

  @Override
  String[] getCustomSpringProperties(){
    return ['-Dspring.mvc.converters.preferred-json-mapper=gson']
  }
}
