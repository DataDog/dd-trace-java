package datadog.trace.agent.tooling.bytebuddy;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Category({"Datadog", "Tracer"})
@Name("datadog.trace.agent.ClassTransformation")
@Label("Class Transformation")
public class ClassTransformationEvent extends Event {
  @Label("Class Name")
  final String className;

  @Label("Class Size before transformation")
  final int classSizeBeforeTransformation;

  @Label("Class Size after transformation")
  int classSizeAfterTransformation;

  @Label("Class Size difference")
  int classSizeDifference;

  @Label("Transformed")
  boolean transformed;

  public static ClassTransformationEvent beforeClassTransformation(
      String className, byte[] classfileBuffer) {
    ClassTransformationEvent event = new ClassTransformationEvent(className, classfileBuffer);
    event.begin();
    return event;
  }

  private ClassTransformationEvent(String className, byte[] classfileBuffer) {
    this.className = className;
    this.classSizeBeforeTransformation = classfileBuffer.length;
  }

  public void afterClassTransformation(byte[] classfileBuffer) {
    if (classfileBuffer == null) {
      classSizeAfterTransformation = classSizeBeforeTransformation;
      transformed = false;
    } else {
      classSizeAfterTransformation = classfileBuffer.length;
      transformed = classSizeBeforeTransformation != classSizeAfterTransformation;
    }
    classSizeDifference = classSizeAfterTransformation - classSizeBeforeTransformation;
    if (shouldCommit()) {
      commit();
    }
  }
}
