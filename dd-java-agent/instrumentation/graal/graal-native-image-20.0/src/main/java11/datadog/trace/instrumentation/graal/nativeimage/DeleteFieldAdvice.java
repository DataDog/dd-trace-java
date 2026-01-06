package datadog.trace.instrumentation.graal.nativeimage;

import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.hosted.substitute.AnnotatedField;
import jdk.vm.ci.meta.ResolvedJavaField;
import net.bytebuddy.asm.Advice;

public class DeleteFieldAdvice {
  @Advice.OnMethodExit
  public static void onExit(
      @Advice.Argument(0) ResolvedJavaField field,
      @Advice.Return(readOnly = false) ResolvedJavaField result,
      @Advice.FieldValue("SUBSTITUTION_DELETE") Delete SUBSTITUTION_DELETE) {
    if ("datadog.trace.bootstrap.DatadogClassLoader"
        .equals(field.getDeclaringClass().toClassName())) {
      result = new AnnotatedField(field, SUBSTITUTION_DELETE);
    }
  }
}
