package datadog.smoketest

import datadog.trace.test.agent.decoder.DecodedSpan
import groovy.json.JsonSlurper
import okhttp3.MediaType

import java.util.concurrent.TimeoutException
import java.util.function.Function
import java.util.function.Predicate

abstract class AbstractSpringBootIastTest extends AbstractServerSmokeTest {

  protected static final String TAG_NAME = '_dd.iast.json'

  protected static final MediaType JSON = MediaType.parse("application/json; charset=utf-8")

  protected static Function<DecodedSpan, Boolean> hasMetric(final String name, final Object value) {
    return { span -> value == span.metrics.get(name) }
  }

  protected static Function<DecodedSpan, Boolean> hasVulnerability(final Predicate<?> predicate) {
    return { span ->
      final iastMeta = span.meta.get(TAG_NAME)
      if (!iastMeta) {
        return false
      }
      final vulnerabilities = parseVulnerabilities(iastMeta)
      return vulnerabilities.stream().anyMatch(predicate)
    }
  }

  protected boolean hasVulnerabilityInLogs(final Predicate<?> predicate) {
    def found = false
    checkLogPostExit { final String log ->
      final index = log.indexOf(TAG_NAME)
      if (index >= 0) {
        final vulnerabilities = parseVulnerabilities(log, index)
        found |= vulnerabilities.stream().anyMatch(predicate)
      }
    }
    return found
  }

  protected void hasTainted(final Closure<Boolean> matcher) {
    final slurper = new JsonSlurper()
    final tainteds = []
    try {
      processTestLogLines { String log ->
        final index = log.indexOf('tainted=')
        if (index >= 0) {
          final tainted = slurper.parse(new StringReader(log.substring(index + 8)))
          tainteds.add(tainted)
          if (matcher.call(tainted)) {
            return true // found
          }
        }
      }
    } catch (TimeoutException toe) {
      throw new AssertionError("No matching tainted found. Tainteds found: ${tainteds}")
    }
  }


  protected static Collection<?> parseVulnerabilities(final String log, final int startIndex) {
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
  }

  protected static Collection<?> parseVulnerabilities(final String iastJson) {
    final slurper = new JsonSlurper()
    final parsed = slurper.parseText(iastJson)
    return parsed['vulnerabilities'] as Collection
  }

  protected static Predicate<?> type(final String type) {
    return { vul ->
      vul.type == type
    }
  }

  protected static Predicate<?> evidence(final String value) {
    return { vul ->
      vul.evidence.value == value
    }
  }

  protected static Predicate<?> withSpan() {
    return { vul ->
      vul.location.spanId > 0
    }
  }

  protected static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }
}
