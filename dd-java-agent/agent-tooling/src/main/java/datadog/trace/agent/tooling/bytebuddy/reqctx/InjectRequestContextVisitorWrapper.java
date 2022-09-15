package datadog.trace.agent.tooling.bytebuddy.reqctx;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.pool.TypePool;

public class InjectRequestContextVisitorWrapper implements AsmVisitorWrapper {
  @Override
  public int mergeWriter(int flags) {
    return flags;
  }

  @Override
  public int mergeReader(int flags) {
    return flags | ClassReader.EXPAND_FRAMES;
  }

  @Override
  public ClassVisitor wrap(
      TypeDescription instrumentedType,
      ClassVisitor classVisitor,
      Implementation.Context implementationContext,
      TypePool typePool,
      FieldList<FieldDescription.InDefinedShape> fields,
      MethodList<?> methods,
      int writerFlags,
      int readerFlags) {
    AnnotationList annotations = instrumentedType.getDeclaredAnnotations();
    AnnotationDescription.Loadable<RequiresRequestContext> rrc =
        annotations.ofType(RequiresRequestContext.class);
    RequestContextSlot slot = rrc.load().value();
    return InjectRequestContextVisitor.createVisitor(classVisitor, methods, slot);
  }
}
