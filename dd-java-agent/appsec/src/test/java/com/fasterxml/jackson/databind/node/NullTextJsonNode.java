package com.fasterxml.jackson.databind.node;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A text node whose textual value is {@code null}. */
public class NullTextJsonNode extends BaseJsonNode {
  @Override
  public JsonNodeType getNodeType() {
    return JsonNodeType.STRING;
  }

  @Override
  public String textValue() {
    return null;
  }

  @Override
  public JsonToken asToken() {
    return JsonToken.VALUE_STRING;
  }

  @Override
  public void serialize(JsonGenerator gen, SerializerProvider serializers) throws IOException {
    gen.writeNull();
  }

  @Override
  public JsonNode deepCopy() {
    return this;
  }

  @Override
  public String asText() {
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o != null && getClass() == o.getClass();
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  @Override
  public void serializeWithType(
      JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer)
      throws IOException {
    gen.writeNull();
  }

  @Override
  public JsonNode get(int index) {
    return null;
  }

  @Override
  public JsonNode path(String fieldName) {
    return MissingNode.getInstance();
  }

  @Override
  public JsonNode path(int index) {
    return MissingNode.getInstance();
  }

  @Override
  protected JsonNode _at(JsonPointer ptr) {
    return MissingNode.getInstance();
  }

  @Override
  public JsonNode findValue(String fieldName) {
    return null;
  }

  @Override
  public JsonNode findParent(String fieldName) {
    return null;
  }

  @Override
  public List<JsonNode> findValues(String fieldName, List<JsonNode> foundSoFar) {
    return foundSoFar != null ? foundSoFar : new ArrayList<JsonNode>();
  }

  @Override
  public List<String> findValuesAsText(String fieldName, List<String> foundSoFar) {
    return foundSoFar != null ? foundSoFar : new ArrayList<String>();
  }

  @Override
  public List<JsonNode> findParents(String fieldName, List<JsonNode> foundSoFar) {
    return foundSoFar != null ? foundSoFar : new ArrayList<JsonNode>();
  }
}
