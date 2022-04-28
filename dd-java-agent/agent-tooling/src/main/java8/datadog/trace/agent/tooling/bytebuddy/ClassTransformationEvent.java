package datadog.trace.agent.tooling.bytebuddy;

import jdk.jfr.BooleanFlag;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Category({"Datadog", "Tracer"})
@Name("datadog.trace.agent.ClassTransformation")
@Label("Class Transformation")
@StackTrace(false)
@Enabled(true)
public class ClassTransformationEvent extends Event {

  private static boolean ENABLED = true;

  @Label("Class Name")
  final String className;

  @Label("Class Size before transformation")
  final int classSizeBeforeTransformation;

  @Label("Class Size after transformation")
  int classSizeAfterTransformation;

  @Label("Class Size difference")
  int classSizeDifference;

  @Label("Transformed")
  @BooleanFlag
  boolean transformed;

  private static ClassTransformationEvent NOOP =
      new ClassTransformationEvent() {
        @Override
        public void afterClassTransformation(byte[] classfileBuffer) {}
      };

  public static ClassTransformationEvent beforeClassTransformation(
      String className, byte[] classfileBuffer, ClassLoader classLoader) {
    if (ENABLED) {
      ClassTransformationEvent event = new ClassTransformationEvent(className, classfileBuffer);
      event.begin();
      return event;
    }
    return NOOP;
  }

  private ClassTransformationEvent() {
    className = null;
    classSizeBeforeTransformation = 0;
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
