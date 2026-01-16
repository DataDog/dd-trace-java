package com.fasterxml.jackson.databind.node

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonPointer
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.jsontype.TypeSerializer

class NullTextJsonNode extends BaseJsonNode {
  @Override
  JsonNodeType getNodeType() {
    return JsonNodeType.STRING
  }

  @Override
  String textValue() {
    return null
  }

  @Override
  JsonToken asToken() {
    return JsonToken.VALUE_STRING
  }

  @Override
  void serialize(JsonGenerator gen, SerializerProvider serializers) throws IOException {
    gen.writeNull()
  }

  @Override
  JsonNode deepCopy() {
    return this
  }

  @Override
  String asText() {
    return null
  }

  @Override
  boolean equals(Object o) {
    if (this.is(o)) {
      return true
    }
    return o != null && getClass() == o.class
  }

  @Override
  int hashCode() {
    return getClass().hashCode()
  }

  @Override
  void serializeWithType(JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer) throws IOException {
    gen.writeNull()
  }

  @Override
  JsonNode get(int index) {
    return null
  }

  @Override
  JsonNode path(String fieldName) {
    return MissingNode.getInstance()
  }

  @Override
  JsonNode path(int index) {
    return MissingNode.getInstance()
  }

  @Override
  @SuppressWarnings('MethodName') // Inherited from Jackson's BaseJsonNode
  protected JsonNode _at(JsonPointer ptr) {
    return MissingNode.getInstance()
  }

  @Override
  JsonNode findValue(String fieldName) {
    return null
  }

  @Override
  JsonNode findParent(String fieldName) {
    return null
  }

  @Override
  List<JsonNode> findValues(String fieldName, List<JsonNode> foundSoFar) {
    return foundSoFar != null ? foundSoFar : new ArrayList<JsonNode>()
  }

  @Override
  List<String> findValuesAsText(String fieldName, List<String> foundSoFar) {
    return foundSoFar != null ? foundSoFar : new ArrayList<String>()
  }

  @Override
  List<JsonNode> findParents(String fieldName, List<JsonNode> foundSoFar) {
    return foundSoFar != null ? foundSoFar : new ArrayList<JsonNode>()
  }
}
