package com.datadog.appsec.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.node.ArrayNode
import com.flipkart.zjsonpatch.DiffFlags
import com.flipkart.zjsonpatch.JsonDiff
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.hamcrest.Matcher

import static com.flipkart.zjsonpatch.DiffFlags.OMIT_COPY_OPERATION

/**
 * Adapted from WireMock's EqualToJsonPattern:
 *
 * LICENSE: https://github.com/tomakehurst/wiremock/blob/28c5e6737caa7887e2070033bd361501e12ea3ee/LICENSE.txt
 * Original: https://github.com/tomakehurst/wiremock/blob/28c5e6737caa7887e2070033bd361501e12ea3ee/src/main/java/com/github/tomakehurst/wiremock/matching/EqualToJsonPattern.java
 *
 */
class JsonMatcher extends BaseMatcher<String> {

  private final static ObjectReader READER = new ObjectMapper().readerFor(JsonNode)
  private final JsonNode expected
  private final boolean ignoreArrayOrder
  boolean ignoreExtraElements

  private JsonMatcher(String expectedJson, boolean ignoreArrayOrder, boolean ignoreExtraElements) {
    this.expected = READER.readTree(expectedJson)
    this.ignoreArrayOrder = ignoreArrayOrder
    this.ignoreExtraElements = ignoreExtraElements
  }

  static Matcher<String> matchesJson(String expectedJson) {
    return new JsonMatcher(expectedJson, false, false)
  }

  static Matcher<String> matchesJson(String expectedJson,
    boolean ignoreArrayOrder,
    boolean ignoreExtraElements) {
    return new JsonMatcher(expectedJson, ignoreArrayOrder, ignoreExtraElements)
  }

  @Override
  boolean matches(Object value) {
    final JsonNode actual = READER.readTree(value as String)


    if (!ignoreArrayOrder && !ignoreExtraElements) {
      return Objects.equals(actual, expected)
    }

    getDistance(actual) == 0.0
  }


  private double getDistance(JsonNode actual) {
    EnumSet<DiffFlags> flags = EnumSet.of(OMIT_COPY_OPERATION)
    ArrayNode diff = JsonDiff.asJson(expected, actual, flags)

    double maxNodes = maxDeepSize(expected, actual)
    return diffSize(diff) / maxNodes
  }


  private static int maxDeepSize(JsonNode one, JsonNode two) {
    Math.max(deepSize(one), deepSize(two))
  }

  private static int deepSize(JsonNode node) {
    if (node == null) {
      return 0
    }

    int acc = 1
    if (node.isContainerNode()) {
      for (JsonNode child : node) {
        acc++
        if (child.isContainerNode()) {
          acc += deepSize(child)
        }
      }
    }

    acc
  }


  private int diffSize(ArrayNode diff) {
    int acc = 0
    for (JsonNode child: diff) {
      String operation = child.findValue('op').textValue()
      JsonNode pathString = getFromPathString(operation, child)
      List<String> path = getPath(pathString.textValue())
      if (!arrayOrderIgnoredAndIsArrayMove(operation, path) && !extraElementsIgnoredAndIsAddition(operation)) {
        JsonNode valueNode = operation == 'remove' ? null : child.findValue('value')
        JsonNode referencedExpectedNode = getNodeAtPath(expected, pathString)
        if (valueNode == null) {
          acc += deepSize(referencedExpectedNode)
        } else {
          acc += maxDeepSize(referencedExpectedNode, valueNode)
        }
      }
    }

    acc
  }

  private static JsonNode getFromPathString(String operation, JsonNode node) {
    if (operation == 'move') {
      return node.findValue('from')
    }

    node.findValue('path')
  }


  private static JsonNode getNodeAtPath(JsonNode rootNode, JsonNode path) {
    String pathString = path.toString() == '"/"' ? '""' : path.toString()
    getNode(rootNode, getPath(pathString), 1)
  }


  private static JsonNode getNode(JsonNode ret, List<String> path, int pos) {
    if (pos >= path.size()) {
      return ret
    }

    if (ret == null) {
      return null
    }

    String key = path.get(pos)
    if (ret.isArray()) {
      int keyInt = Integer.parseInt(key.replaceAll('"', ''))
      return getNode(ret.get(keyInt), path, ++pos)
    } else if (ret.isObject()) {
      if (ret.has(key)) {
        return getNode(ret.get(key), path, ++pos)
      }
      null
    } else {
      ret
    }
  }

  private boolean arrayOrderIgnoredAndIsArrayMove(String operation, List<String> path) {
    operation == 'move' && path[-1].isNumber() && ignoreArrayOrder
  }

  private boolean extraElementsIgnoredAndIsAddition(String operation) {
    operation == 'add' && ignoreExtraElements
  }

  private static List<String> getPath(String path) {
    List<String> paths = path.replaceAll('"', '').split('/') as List
    paths.collect {
      it.replaceAll('~1', '/').replaceAll('~0', '~')
    }
  }

  @Override
  void describeTo(Description description) {
    description.appendText(expected.toPrettyString())
    if (ignoreArrayOrder) {
      description.appendText(' ignoring array order')
    }
    if (ignoreExtraElements) {
      description.appendText(' ignoring extra elements')
    }
  }

  @Override
  void describeMismatch(Object item, Description description) {
    JsonNode actual = READER.readTree item.toString()
    String actualPrinted = actual.toPrettyString()
    description.appendText('was ').appendText(actualPrinted)
    description.appendText('\n\ndiff is ')
    ArrayNode diff = JsonDiff.asJson(this.expected, actual)
    description.appendText(diff.toPrettyString())
  }
}
