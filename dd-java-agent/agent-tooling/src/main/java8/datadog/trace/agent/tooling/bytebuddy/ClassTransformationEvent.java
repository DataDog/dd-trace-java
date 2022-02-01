package datadog.trace.agent.tooling.bytebuddy;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Category({"Data Dog Tracer", "Class Transformation"})
@Name("datadog.trace.agent.ClassTransformation")
@Label("Class Transformation")
public class ClassTransformationEvent extends Event {
  @Label("Class Name")
  String className;

  @Label("Transformed")
  boolean transformed;

  protected ClassTransformationEvent(String className) {
    this.className = className;
  }

  protected void setTransformed(boolean value) {
    transformed = value;
  }
}
