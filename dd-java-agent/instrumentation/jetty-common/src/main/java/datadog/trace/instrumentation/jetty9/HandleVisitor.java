package datadog.trace.instrumentation.jetty9;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jetty.JettyBlockingHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instruments the handle (or run) method to put the calls to <code>getServer().handle(this)</code>
 * under a condition, for the {@link org.eclipse.jetty.server.HttpChannel} class.
 *
 * <p>In particular, for earlier versions of jetty: <code>
 *   case REQUEST_DISPATCH:
 *   // ...
 *   getServer().handle(this);
 * </code> is replaced with: <code>
 *   case REQUEST_DISPATCH:
 *   // ...
 *   if (span != null && span.getBlockingAction() != null &&
 *       JettyBlockingHelper.block(this.getRequest(), this.getResponse())) {
 *     // nothing
 *   } else {
 *     getServer().handle(this);
 *   }
 * </code> And for later versions of Jetty, <code>
 *   case DISPATCH:
 *   {
 *     // ...
 *     dispatch(DispatcherType.REQUEST, () ->
 *       {
 *         // ...
 *         getServer().handle(HttpChannel.this);
 *       });
 * </code> is replaced with: <code>
 *   case DISPATCH:
 *   {
 *     // ...
 *     if (span != null && span.getBlockingAction() != null) {
 *       Request req = getRequest(); // actually on the stack only
 *       Response resp = getResponse(); // idem
 *       dispatch(DispatcherType.REQUEST, () -> {
 *         JettyBlockingHelper.blockAndThrowOnFailure(
 *             request, response, span.getBlockingAction(), span);
 *       });
 *     } else {
 *       dispatch(DispatcherType.REQUEST, () -> {
 *         // ...
 *         getServer().handle(HttpChannel.this);
 *       });
 *   }
 * </code>
 */
public class HandleVisitor extends MethodVisitor {
  private static final Logger log = LoggerFactory.getLogger(HandleVisitor.class);

  private boolean lookForStore;
  private int agentSpanVar = -1;
  private boolean success;
  private final String methodName;

  public HandleVisitor(int api, DelayCertainInsMethodVisitor methodVisitor, String methodName) {
    super(api, methodVisitor);
    this.methodName = methodName;
  }

  DelayCertainInsMethodVisitor delayVisitorDelegate() {
    return (DelayCertainInsMethodVisitor) this.mv;
  }

  @Override
  public void visitMethodInsn(
      int opcode, String owner, String name, String descriptor, boolean isInterface) {
    if (agentSpanVar == -1) {
      lookForStore =
          !lookForStore
              && opcode == Opcodes.INVOKEVIRTUAL
              && name.equals("startSpan")
              && descriptor.endsWith("Ldatadog/trace/bootstrap/instrumentation/api/AgentSpan;");
    } else if (!success
        && opcode == Opcodes.INVOKEVIRTUAL
        && owner.equals("org/eclipse/jetty/server/Server")
        && name.equals("handle")
        && descriptor.equals("(Lorg/eclipse/jetty/server/HttpChannel;)V")) {
      DelayCertainInsMethodVisitor mv = delayVisitorDelegate();
      List<Function> savedVisitations = mv.transferVisitations();
      /*
       * Saved visitations should be for:
       *
       * aload_0
       * invokevirtual #78                 // Method getServer:()Lorg/eclipse/jetty/server/Server;
       * aload_0
       */
      if (savedVisitations.size() != 3) {
        mv.commitVisitations(savedVisitations);
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }

      Label doBlockLabel = new Label();
      Label beforeHandle = new Label();
      Label afterHandle = new Label();

      super.visitVarInsn(Opcodes.ALOAD, agentSpanVar);
      super.visitJumpInsn(Opcodes.IFNULL, beforeHandle);
      super.visitVarInsn(Opcodes.ALOAD, agentSpanVar);
      super.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          "datadog/trace/bootstrap/instrumentation/api/AgentSpan",
          "getRequestBlockingAction",
          "()" + Type.getDescriptor(Flow.Action.RequestBlockingAction.class),
          true);
      super.visitJumpInsn(Opcodes.IFNONNULL, doBlockLabel);
      super.visitJumpInsn(Opcodes.GOTO, beforeHandle);

      super.visitLabel(doBlockLabel);
      super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
      super.visitVarInsn(Opcodes.ALOAD, 0);
      super.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          "org/eclipse/jetty/server/HttpChannel",
          "getRequest",
          "()Lorg/eclipse/jetty/server/Request;",
          false);
      super.visitVarInsn(Opcodes.ALOAD, 0);
      super.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          "org/eclipse/jetty/server/HttpChannel",
          "getResponse",
          "()Lorg/eclipse/jetty/server/Response;",
          false);
      super.visitVarInsn(Opcodes.ALOAD, agentSpanVar);
      super.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          "datadog/trace/bootstrap/instrumentation/api/AgentSpan",
          "getRequestBlockingAction",
          "()" + Type.getDescriptor(Flow.Action.RequestBlockingAction.class),
          true);
      super.visitVarInsn(Opcodes.ALOAD, agentSpanVar);
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          Type.getInternalName(JettyBlockingHelper.class),
          "block",
          "(Lorg/eclipse/jetty/server/Request;Lorg/eclipse/jetty/server/Response;"
              + Type.getDescriptor(Flow.Action.RequestBlockingAction.class)
              + Type.getDescriptor(AgentSpan.class)
              + ")Z",
          false);
      super.visitJumpInsn(Opcodes.IFNE, afterHandle);

      super.visitLabel(beforeHandle);
      super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
      mv.commitVisitations(savedVisitations);
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      super.visitLabel(afterHandle);
      super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
      this.success = true;
      return;
    } else if (!success
        && (opcode == Opcodes.INVOKESPECIAL || opcode == Opcodes.INVOKEVIRTUAL)
        && owner.equals("org/eclipse/jetty/server/HttpChannel")
        && name.equals("dispatch")
        && (descriptor.equals(
                "(Ljavax/servlet/DispatcherType;Lorg/eclipse/jetty/server/HttpChannel$Dispatchable;)V")
            || descriptor.equals(
                "(Ljakarta/servlet/DispatcherType;Lorg/eclipse/jetty/server/HttpChannel$Dispatchable;)V"))) {

      DelayCertainInsMethodVisitor mv = delayVisitorDelegate();
      List<Function> savedVisitations = mv.transferVisitations();

      // check that we've queued up what we're supposed to
      if (savedVisitations.size() != 4
          ||
          // push this to the stack
          !(savedVisitations.get(0) instanceof DelayCertainInsMethodVisitor.ALoadVarInsn)
          ||
          // push DispatcherType.REQUEST to the stack
          !(savedVisitations.get(1) instanceof DelayCertainInsMethodVisitor.GetStaticFieldInsn)
          ||
          // push this to the stack (to create the lambda, which depends on this)
          !(savedVisitations.get(2) instanceof DelayCertainInsMethodVisitor.ALoadVarInsn)
          || !(savedVisitations.get(3) instanceof DelayCertainInsMethodVisitor.InvokeDynamicInsn)) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }

      DelayCertainInsMethodVisitor.GetStaticFieldInsn getStaticFieldInsn =
          (DelayCertainInsMethodVisitor.GetStaticFieldInsn) savedVisitations.get(1);
      if ((!getStaticFieldInsn.owner.equals("javax/servlet/DispatcherType")
              && !getStaticFieldInsn.owner.equals("jakarta/servlet/DispatcherType"))
          || !getStaticFieldInsn.name.equals("REQUEST")) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }

      Label doBlockLabel = new Label();
      Label beforeRegularDispatch = new Label();
      Label afterRegularDispatch = new Label();

      super.visitVarInsn(Opcodes.ALOAD, agentSpanVar);
      super.visitJumpInsn(Opcodes.IFNULL, beforeRegularDispatch);
      super.visitVarInsn(Opcodes.ALOAD, agentSpanVar);
      super.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          "datadog/trace/bootstrap/instrumentation/api/AgentSpan",
          "getRequestBlockingAction",
          "()" + Type.getDescriptor(Flow.Action.RequestBlockingAction.class),
          true);
      super.visitJumpInsn(Opcodes.IFNONNULL, doBlockLabel);
      super.visitJumpInsn(Opcodes.GOTO, beforeRegularDispatch);

      super.visitLabel(doBlockLabel);
      super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
      // dispatch with a Dispatchable created from JettyBlockingHelper::block
      // first set up the first two arguments to dispatch (this and DispatcherType.REQUEST)
      List<Function> loadThisAndEnum = new ArrayList<>(savedVisitations.subList(0, 2));
      mv.commitVisitations(loadThisAndEnum);
      // set up the arguments to the method underlying the lambda (Request, Response,
      // RequestBlockingAction, AgentSpan)
      super.visitVarInsn(Opcodes.ALOAD, 0);
      super.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          "org/eclipse/jetty/server/HttpChannel",
          "getRequest",
          "()Lorg/eclipse/jetty/server/Request;",
          false);
      super.visitVarInsn(Opcodes.ALOAD, 0);
      super.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          "org/eclipse/jetty/server/HttpChannel",
          "getResponse",
          "()Lorg/eclipse/jetty/server/Response;",
          false);
      super.visitVarInsn(Opcodes.ALOAD, agentSpanVar);
      super.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          "datadog/trace/bootstrap/instrumentation/api/AgentSpan",
          "getRequestBlockingAction",
          "()" + Type.getDescriptor(Flow.Action.RequestBlockingAction.class),
          true);
      super.visitVarInsn(Opcodes.ALOAD, agentSpanVar);

      // create the lambda
      super.visitInvokeDynamicInsn(
          "dispatch",
          "(Lorg/eclipse/jetty/server/Request;Lorg/eclipse/jetty/server/Response;"
              + Type.getDescriptor(Flow.Action.RequestBlockingAction.class)
              + Type.getDescriptor(AgentSpan.class)
              + ")Lorg/eclipse/jetty/server/HttpChannel$Dispatchable;",
          new Handle(
              Opcodes.H_INVOKESTATIC,
              "java/lang/invoke/LambdaMetafactory",
              "metafactory",
              "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
              false),
          new Object[] {
            Type.getType("()V"),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                Type.getInternalName(JettyBlockingHelper.class),
                "blockAndThrowOnFailure",
                "(Lorg/eclipse/jetty/server/Request;Lorg/eclipse/jetty/server/Response;"
                    + Type.getDescriptor(Flow.Action.RequestBlockingAction.class)
                    + Type.getDescriptor(AgentSpan.class)
                    + ")V",
                false),
            Type.getType("()V")
          });

      // invoke the dispatch method
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

      super.visitJumpInsn(Opcodes.GOTO, afterRegularDispatch);

      super.visitLabel(beforeRegularDispatch);
      super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
      mv.commitVisitations(savedVisitations);
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      super.visitLabel(afterRegularDispatch);
      super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
      this.success = true;
      return;
    }

    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
  }

  private static List<Object> fromArray(Object[] obj) {
    if (obj == null) {
      return new ArrayList<>();
    }
    return new ArrayList<>(Arrays.asList(obj));
  }

  @Override
  public void visitVarInsn(int opcode, int varIndex) {
    if (lookForStore && opcode == Opcodes.ASTORE) {
      agentSpanVar = varIndex;
      lookForStore = false;
    }

    super.visitVarInsn(opcode, varIndex);
  }

  @Override
  public void visitEnd() {
    if (!success && !"run".equals(methodName)) {
      log.warn(
          "Transformation of Jetty's connection class was not successful. Blocking will likely not work");
    }
    super.visitEnd();
  }
}
