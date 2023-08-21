package datadog.smoketest

import datadog.smoketest.model.TaintedObject
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import spock.lang.Shared

import java.util.function.Predicate

@CompileDynamic
abstract class AbstractIastServerSmokeTest extends AbstractServerSmokeTest {

  @Shared
  private final JsonSlurper jsonSlurper = new JsonSlurper()

  @Override
  String logLevel() {
    return 'debug'
  }

  protected void hasTainted(final Predicate<TaintedObject> matcher) {
    processTestLogLines { String log ->
      final index = log.indexOf('tainted=')
      if (index >= 0) {
        final tainted = jsonSlurper.parse(new StringReader(log.substring(index + 8))) as TaintedObject
        return matcher.test(tainted)
      }
      return false
    }
  }

  protected static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }
}
