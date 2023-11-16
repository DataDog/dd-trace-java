package datadog.smoketest

class GsonIastSpringBootSmokeTest extends  AbstractGsonIastSpringBootSmokeTest{

  @Override
  String[] getCustomSpringProperties(){
    return ['-Dspring.http.converters.preferred-json-mapper=gson']
  }
}
