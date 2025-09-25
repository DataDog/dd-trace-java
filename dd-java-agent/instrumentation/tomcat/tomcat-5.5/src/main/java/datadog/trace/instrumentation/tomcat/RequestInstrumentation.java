package datadog.trace.instrumentation.tomcat;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.tomcat.TomcatDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import org.apache.catalina.connector.CoyoteAdapter;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

@AutoService(InstrumenterModule.class)
public final class RequestInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {

  public RequestInstrumentation() {
    super("tomcat");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.catalina.connector.Request";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".TomcatDecorator",
      packageName + ".TomcatDecorator$TomcatBlockResponseFunction",
      packageName + ".TomcatBlockingHelper",
      packageName + ".RequestURIDataAdapter",
    };
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    // old versions of Catalina (from 2006 and before) suppress all throwables
    // in parseParameters(). We let our BlockingException go through
    transformer.applyAdvice(new ThrowableCaughtVisitorWrapper());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("recycle").and(takesNoArguments()),
        RequestInstrumentation.class.getName() + "$RecycleAdvice");
  }

  /**
   * Tomcat recycles request/response objects after the response is sent. This provides a reliable
   * point to finish the server span at the last possible moment.
   */
  public static class RecycleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final Request req) {
      Response resp = req.getResponse();

      Object spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);

      if (spanObj instanceof AgentSpan) {
        /**
         * This advice will be called for both Request and Response. The span is removed from the
         * request so the advice only applies the first invocation. (So it doesn't matter which is
         * recycled first.)
         */
        // value set on the coyote request, so we must remove directly from there.
        req.getCoyoteRequest().setAttribute(DD_SPAN_ATTRIBUTE, null);

        final AgentSpan span = (AgentSpan) spanObj;
        DECORATE.onResponse(span, resp);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }

    private void muzzleCheck(CoyoteAdapter adapter, Request request) throws Exception {
      adapter.service(null, null);
      request.recycle();
    }
  }

  public static class ThrowableCaughtVisitorWrapper implements AsmVisitorWrapper {
    @Override
    public int mergeWriter(int flags) {
      return flags;
    }

    @Override
    public int mergeReader(int flags) {
      return flags;
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
      if (implementationContext.getClassFileVersion().equals(ClassFileVersion.JAVA_V4)) {
        return new ThrowableCaughtVisitor(classVisitor);
      } else {
        return classVisitor;
      }
    }
  }

  public static class ThrowableCaughtVisitor extends ClassVisitor {
    protected ThrowableCaughtVisitor(ClassVisitor classVisitor) {
      super(Opcodes.ASM4, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor superMethodVisitor =
          super.visitMethod(access, name, descriptor, signature, exceptions);
      if (name.equals("parseParameters") && "()V".equals(descriptor)) {
        return new ParseParametersMethodVisitor(superMethodVisitor);
      } else {
        return superMethodVisitor;
      }
    }
  }

  public static class ParseParametersMethodVisitor extends MethodVisitor {
    public ParseParametersMethodVisitor(MethodVisitor superMethodVisitor) {
      super(Opcodes.ASM4, superMethodVisitor);
    }

    private Label throwableHandler;
    private Label throwLabel;

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      super.visitTryCatchBlock(start, end, handler, type);
      if ("java/lang/Throwable".equals(type)) {
        throwableHandler = handler;
      }
    }

    @Override
    public void visitLabel(Label label) {
      super.visitLabel(label);
      if (label.equals(throwableHandler)) {
        visitInsn(Opcodes.DUP);
        visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(BlockingException.class));
        throwLabel = new Label();
        visitJumpInsn(Opcodes.IFNE, throwLabel);
      }
    }

    @Override
    public void visitEnd() {
      if (throwLabel != null) {
        super.visitLabel(throwLabel);
        visitInsn(Opcodes.ATHROW);
      }
      super.visitEnd();
    }
  }
}
