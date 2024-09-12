package datadog.trace.payloadtags;

import org.jsfr.json.path.ArrayIndex;
import org.jsfr.json.path.ChildNode;
import org.jsfr.json.path.JsonPath;
import org.jsfr.json.path.PathOperator;

class JsonPosition extends JsonPath {

  static JsonPosition start() {
    return new JsonPosition();
  }

  void stepIntoObject() {
    if (operators.length > size) {
      PathOperator next = operators[size];
      if (next instanceof ChildNode) {
        size++;
        ((ChildNode) next).setKey(null);
        return;
      }
    }
    push(new ChildNode(null));
  }

  void updateObjectEntry(String key) {
    ((ChildNode) peek()).setKey(key);
  }

  void stepOutObject() {
    pop();
  }

  void stepIntoArray() {
    if (operators.length > size) {
      PathOperator next = operators[size];
      if (next instanceof ArrayIndex) {
        size++;
        ((ArrayIndex) next).reset();
        return;
      }
    }
    push(new ArrayIndex());
  }

  boolean accumulateArrayIndex() {
    PathOperator top = this.peek();
    if (top.getType() == PathOperator.Type.ARRAY) {
      ((ArrayIndex) top).increaseArrayIndex();
      return true;
    }
    return false;
  }

  void stepOutArray() {
    pop();
  }
}
