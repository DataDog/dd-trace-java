package datadog.smoketest

import datadog.smoketest.model.TaintedObject
import datadog.smoketest.model.Vulnerability
import datadog.smoketest.model.Vulnerability.Source
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.spockframework.runtime.SpockTimeoutError
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeoutException

@CompileDynamic
abstract class AbstractIastServerSmokeTest extends AbstractServerSmokeTest {

  private static final String TAG_NAME = '_dd.iast.json'
  private static final String IAST_STARTED_MSG = 'IAST started'

  @Shared
  private final JsonSlurper jsonSlurper = new JsonSlurper()

  @Override
  String logLevel() {
    return 'debug'
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  def setupSpec() {
    try {
      processTestLogLines { it.contains(IAST_STARTED_MSG) }
    } catch (TimeoutException toe) {
      throw new AssertionError("'$IAST_STARTED_MSG' not found in logs", toe)
    }
  }

  protected static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }

  protected void hasTainted(@ClosureParams(value = SimpleType, options = ['datadog.smoketest.model.TaintedObject'])
    final Closure<Boolean> matcher) {
    boolean found = false
    final tainteds = []
    final closure = { String log ->
      final tainted = parseTaintedLog(log)
      if (tainted != null) {
        tainteds.add(tainted)
        if (matcher.call(tainted)) {
          found = true
          return true // found
        }
      }
      return false
    }
    try {
      processTestLogLines(closure)
    } catch (TimeoutException toe) {
      assert found, "No matching tainted found. Tainteds found: ${new JsonBuilder(tainteds).toPrettyString()}"
    }
  }

  /**
   * Use {@link #hasVulnerability(groovy.lang.Closure)} if possible
   */
  @Deprecated
  protected void hasVulnerabilityInLogs(@ClosureParams(value = SimpleType, options = ['datadog.smoketest.model.Vulnerability'])
    final Closure<Boolean> matcher) {
    boolean found = false
    final vulnerabilities = []
    final closure = { String log ->
      final parsed = parseVulnerabilitiesLog(log)
      if (parsed != null) {
        vulnerabilities.addAll(parsed)
        if (parsed.find(matcher) != null) {
          found = true
          return true
        }
      }
      return false
    }
    try {
      processTestLogLines(closure)
    } catch (TimeoutException toe) {
      assert found, "No matching vulnerability found. Vulnerabilities found: ${new JsonBuilder(vulnerabilities).toPrettyString()}"
    }
  }

  protected void hasMetric(final String name, final Object value) {
    final found = []
    try {
      waitForSpan(pollingConditions()) { span ->
        if (span.metrics.containsKey(name)) {
          final metric = span.metrics.get(name)
          found.add(metric)
          if (metric == value) {
            return true
          }
        }
        return false
      }
    } catch (SpockTimeoutError toe) {
      throw new AssertionError("No matching metric with name $name found. Metrics found: ${new JsonBuilder(found).toPrettyString()}")
    }
  }

  protected void hasMeta(final String name) {
    try {
      waitForSpan(pollingConditions()) { span ->
        return span.meta.containsKey(name)
      }
    } catch (SpockTimeoutError toe) {
      throw new AssertionError("No matching meta with name $name found")
    }
  }

  protected void hasVulnerability(@ClosureParams(value = SimpleType, options = ['datadog.smoketest.model.Vulnerability'])
    final Closure<Boolean> matcher) {
    final found = []
    try {
      waitForSpan(pollingConditions()) { span ->
        final json = span.meta.get(TAG_NAME)
        if (!json) {
          return false
        }
        final vulnerabilities = parseVulnerabilities(json)
        found.addAll(vulnerabilities)
        return vulnerabilities.find(matcher) != null
      }
    } catch (SpockTimeoutError toe) {
      throw new AssertionError("No matching vulnerability found. Vulnerabilities found: ${new JsonBuilder(found).toPrettyString()}")
    }
  }

  protected void hasVulnerabilityStack() {
    try {
      waitForSpan(pollingConditions()) { span ->
        final json = span.meta.get(TAG_NAME)
        if (!json) {
          return false
        }
        final vulnerabilities = parseVulnerabilities(json)
        def stack = span.metaStruct.get('_dd.stack')
        if (stack == null) {
          return false
        }
        def stackVulnerabilities = stack.get('vulnerability')
        if (stackVulnerabilities == null) {
          return false
        }
        return stackVulnerabilities.size() == vulnerabilities.size()
      }
    } catch (SpockTimeoutError toe) {
      throw new AssertionError("Some error occurred while checking vulnerability stack")
    }
  }

  protected void noVulnerability(@ClosureParams(value = SimpleType, options = ['datadog.smoketest.model.Vulnerability'])
    final Closure<Boolean> matcher) {
    final found = []
    try {
      waitForSpan(pollingConditions()) { span ->
        final json = span.meta.get(TAG_NAME)
        if (!json) {
          return false
        }
        return parseVulnerabilities(json)
      }
    } catch (SpockTimeoutError toe) {
      // do nothing
    }
    if (found.find(matcher) != null) {
      throw new AssertionError("A matching vulnerability was found while expecting none. Vulnerabilities found: ${new JsonBuilder(found).toPrettyString()}")
    }
  }

  protected TaintedObject parseTaintedLog(final String log) {
    final index = log.indexOf('tainted=')
    if (index >= 0) {
      try {
        final tainted = jsonSlurper.parse(new StringReader(log.substring(index + 8)))
        if (tainted instanceof Map) {
          return tainted as TaintedObject
        }
      } catch (Exception e) {
      }
    }
    return null
  }

  protected List<Vulnerability> parseVulnerabilitiesLog(final String log) {
    final startIndex = log.indexOf(TAG_NAME)
    if (startIndex > 0) {
      try {
        final chars = log.toCharArray()
        final builder = new StringBuilder()
        def level = 0
        for (int i = log.indexOf('{', startIndex); i < chars.length; i++) {
          final current = chars[i]
          if (current == '{' as char) {
            level++
          } else if (current == '}' as char) {
            level--
          }
          builder.append(chars[i])
          if (level == 0) {
            break
          }
        }
        return parseVulnerabilities(builder.toString())
      } catch (Exception e) {
      }
    }
    return null
  }

  protected PollingConditions pollingConditions() {
    return new PollingConditions(timeout: 5)
  }

  protected List<Vulnerability> parseVulnerabilities(final String json) {
    final batch = jsonSlurper.parseText(json)
    if (!(batch instanceof Map)) {
      return []
    }
    final vulnerabilities = batch.vulnerabilities as List<Vulnerability>
    final sources = batch.sources as List<Source>
    vulnerabilities.each {
      final evidence = it.evidence
      if (evidence?.valueParts != null) {
        evidence.valueParts.each {
          if (it.source != null) {
            final index = it.source as Integer
            it.source = sources[index]
          }
        }
      }
    }
    return vulnerabilities
  }
}
