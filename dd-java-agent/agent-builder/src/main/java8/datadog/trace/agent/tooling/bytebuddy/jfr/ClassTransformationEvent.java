package datadog.trace.agent.tooling.bytebuddy.jfr;

import jdk.jfr.BooleanFlag;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Category({"Datadog", "Tracer"})
@Name("datadog.trace.agent.ClassTransformation")
@Label("Class Transformation")
@StackTrace(false)
public final class ClassTransformationEvent extends Event {

  public static ClassTransformationEvent beforeClassTransformation(String fqcn, byte[] classBytes) {
    ClassTransformationEvent evt = new ClassTransformationEvent(fqcn, classBytes.length);
    evt.begin();
    return evt;
  }

  @Label("FQCN")
  final String fqcn;

  @Label("Class Size")
  final int classSize;

  @Label("Class Size Difference")
  int classSizeDifference;

  @Label("Transformed")
  @BooleanFlag
  boolean transformed;

  public void afterClassTransformation(byte[] classBytesAfterTransformation) {
    if (classBytesAfterTransformation != null) {
      classSizeDifference = classBytesAfterTransformation.length - classSize;
      transformed = classSizeDifference != 0;
    }
    if (shouldCommit()) {
      commit();
    }
  }

  private ClassTransformationEvent(String fqcn, int classSize) {
    this.fqcn = fqcn;
    this.classSize = classSize;
    this.classSizeDifference = 0;
    this.transformed = false;
  }
}
