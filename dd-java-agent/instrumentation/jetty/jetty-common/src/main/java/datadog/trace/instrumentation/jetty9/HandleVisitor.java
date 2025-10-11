package datadog.trace.instrumentation.jetty9;

import static net.bytebuddy.jar.asm.Opcodes.ALOAD;
import static net.bytebuddy.jar.asm.Opcodes.F_SAME;
import static net.bytebuddy.jar.asm.Opcodes.GOTO;
import static net.bytebuddy.jar.asm.Opcodes.H_INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.IFEQ;
import static net.bytebuddy.jar.asm.Opcodes.IFNE;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge;
import datadog.trace.instrumentation.jetty.JettyBlockingHelper;
import java.util.ArrayList;
import java.util.List;
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
 * <p>In particular, for earlier versions of jetty:
 *
 * <pre>
 *   case REQUEST_DISPATCH:
 *   // ...
 *   getServer().handle(this);
 * </pre>
 *
 * is replaced with:
 *
 * <pre>
 *   case REQUEST_DISPATCH:
 *   // ...
 *   if (JettyBlockingHelper.block(this.getRequest(), this.getResponse(), Java8BytecodeBridge.getCurrentContext())) {
 *     // nothing
 *   } else {
 *     getServer().handle(this);
 *   }
 * </pre>
 *
 * And for later versions of Jetty before 11.16.0,
 *
 * <pre>
 *   case DISPATCH:
 *   {
 *     // ...
 *     dispatch(DispatcherType.REQUEST, () ->
 *       {
 *         // ...
 *         getServer().handle(HttpChannel.this);
 *       });
 * </pre>
 *
 * is replaced with:
 *
 * <pre>
 *   case DISPATCH:
 *   {
 *     // ...
 *     if (JettyBlockingHelper.hasBlockingRequest(Java8BytecodeBridge.getCurrentContext()) {
 *       Request req = getRequest(); // actually on the stack only
 *       Response resp = getResponse(); // idem
 *       Context context = Java8BytecodeBridge.getCurrentContext() // idem
 *       dispatch(DispatcherType.REQUEST, () -> {
 *         JettyBlockingHelper.blockAndThrowOnFailure(request, response, context);
 *       });
 *     } else {
 *       dispatch(DispatcherType.REQUEST, () -> {
 *         // ...
 *         getServer().handle(HttpChannel.this);
 *       });
 *   }
 * </pre>
 *
 * And for later versions of Jetty,
 *
 * <pre>
 *   case DISPATCH:
 *   {
 *     // ...
 *     dispatch(DispatcherType.REQUEST, _requestDispatcher);
 * </pre>
 *
 * is replaced with:
 *
 * <pre>
 *   case DISPATCH:
 *   {
 *     // ...
 *     if (JettyBlockingHelper.hasBlockingRequest(Java8BytecodeBridge.getCurrentContext()) {
 *       Request req = getRequest(); // actually on the stack only
 *       Response resp = getResponse(); // idem
 *       Context context = Java8BytecodeBridge.getCurrentContext() // idem
 *       dispatch(DispatcherType.REQUEST, () -> {
 *         JettyBlockingHelper.blockAndThrowOnFailure(request, response, context);
 *       });
 *     } else {
 *       dispatch(DispatcherType.REQUEST, _requestDispatcher);
 *   }
 * </pre>
 */
public class HandleVisitor extends MethodVisitor {
  private static final Logger log = LoggerFactory.getLogger(HandleVisitor.class);

  /** Whether the handle() method injection was successful . */
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
    if (!success
        && opcode == INVOKEVIRTUAL
        && owner.equals("org/eclipse/jetty/server/Server")
        && name.equals("handle")
        && descriptor.equals("(Lorg/eclipse/jetty/server/HttpChannel;)V")) {
      DelayCertainInsMethodVisitor mv = delayVisitorDelegate();
      List<Runnable> savedVisitations = mv.transferVisitations();
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

      // Declare label to insert after Server.handle() call
      Label afterHandle = new Label();
      // Inject blocking helper call and get its three parameters onto the stack:
      // - Request
      // - Response
      // - Context -- retrieved from current as attached just earlier from tracing instrumentation
      super.visitVarInsn(ALOAD, 0);
      super.visitMethodInsn(
          INVOKEVIRTUAL,
          "org/eclipse/jetty/server/HttpChannel",
          "getRequest",
          "()Lorg/eclipse/jetty/server/Request;",
          false);
      super.visitVarInsn(ALOAD, 0);
      super.visitMethodInsn(
          INVOKEVIRTUAL,
          "org/eclipse/jetty/server/HttpChannel",
          "getResponse",
          "()Lorg/eclipse/jetty/server/Response;",
          false);
      super.visitMethodInsn(
          INVOKESTATIC,
          Type.getInternalName(Java8BytecodeBridge.class),
          "getCurrentContext",
          "()Ldatadog/context/Context;",
          false);
      super.visitMethodInsn(
          INVOKESTATIC,
          Type.getInternalName(JettyBlockingHelper.class),
          "block",
          "(Lorg/eclipse/jetty/server/Request;Lorg/eclipse/jetty/server/Response;"
              + Type.getDescriptor(Context.class)
              + ")Z",
          false);
      // Inject jump to after Server.handle() call if blocked
      super.visitJumpInsn(IFNE, afterHandle);
      // Inject getServer() and Server.handle() calls
      mv.commitVisitations(savedVisitations);
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      // Inject label after Server.handle() call to jump here when blocked
      super.visitLabel(afterHandle);
      super.visitFrame(F_SAME, 0, null, 0, null);
      this.success = true;
      return;
    } else if (!success
        && (opcode == Opcodes.INVOKESPECIAL || opcode == INVOKEVIRTUAL)
        && owner.equals("org/eclipse/jetty/server/HttpChannel")
        && name.equals("dispatch")
        && (descriptor.equals(
                "(Ljavax/servlet/DispatcherType;Lorg/eclipse/jetty/server/HttpChannel$Dispatchable;)V")
            || descriptor.equals(
                "(Ljakarta/servlet/DispatcherType;Lorg/eclipse/jetty/server/HttpChannel$Dispatchable;)V"))) {

      DelayCertainInsMethodVisitor mv = delayVisitorDelegate();
      List<Runnable> savedVisitations = mv.transferVisitations();

      // check that we've queued up what we're supposed to
      if (!checkDispatchMethodState(savedVisitations)) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }

      Label beforeRegularDispatch = new Label();
      Label afterRegularDispatch = new Label();

      // Add current context to the stack
      super.visitMethodInsn(
          INVOKESTATIC,
          Type.getInternalName(Java8BytecodeBridge.class),
          "getCurrentContext",
          "()Ldatadog/context/Context;",
          false);
      // Call JettyBlockingHelper.hasRequestBlockingAction(context)
      super.visitMethodInsn(
          INVOKESTATIC,
          Type.getInternalName(JettyBlockingHelper.class),
          "hasRequestBlockingAction",
          "(" + Type.getDescriptor(Context.class) + ")Z",
          false);
      // If no request blocking action, jump before the regular dispatch
      super.visitJumpInsn(IFEQ, beforeRegularDispatch);

      // dispatch with a Dispatchable created from JettyBlockingHelper::block
      // first set up the first two arguments to dispatch (this and DispatcherType.REQUEST)
      List<Runnable> loadThisAndEnum = new ArrayList<>(savedVisitations.subList(0, 2));
      mv.commitVisitations(loadThisAndEnum);
      // set up the arguments to the method underlying the lambda (Request, Response, Context)
      super.visitVarInsn(ALOAD, 0);
      super.visitMethodInsn(
          INVOKEVIRTUAL,
          "org/eclipse/jetty/server/HttpChannel",
          "getRequest",
          "()Lorg/eclipse/jetty/server/Request;",
          false);
      super.visitVarInsn(ALOAD, 0);
      super.visitMethodInsn(
          INVOKEVIRTUAL,
          "org/eclipse/jetty/server/HttpChannel",
          "getResponse",
          "()Lorg/eclipse/jetty/server/Response;",
          false);
      super.visitMethodInsn(
          INVOKESTATIC,
          Type.getInternalName(Java8BytecodeBridge.class),
          "getCurrentContext",
          "()Ldatadog/context/Context;",
          false);

      // create the lambda
      super.visitInvokeDynamicInsn(
          "dispatch",
          "(Lorg/eclipse/jetty/server/Request;Lorg/eclipse/jetty/server/Response;"
              + Type.getDescriptor(Context.class)
              + ")Lorg/eclipse/jetty/server/HttpChannel$Dispatchable;",
          new Handle(
              H_INVOKESTATIC,
              "java/lang/invoke/LambdaMetafactory",
              "metafactory",
              "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
              false),
          new Object[] {
            Type.getType("()V"),
            new Handle(
                H_INVOKESTATIC,
                Type.getInternalName(JettyBlockingHelper.class),
                "blockAndThrowOnFailure",
                "(Lorg/eclipse/jetty/server/Request;Lorg/eclipse/jetty/server/Response;"
                    + Type.getDescriptor(Context.class)
                    + ")V",
                false),
            Type.getType("()V")
          });

      // invoke the dispatch method
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

      super.visitJumpInsn(GOTO, afterRegularDispatch);

      super.visitLabel(beforeRegularDispatch);
      super.visitFrame(F_SAME, 0, null, 0, null);
      mv.commitVisitations(savedVisitations);
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      super.visitLabel(afterRegularDispatch);
      super.visitFrame(F_SAME, 0, null, 0, null);
      this.success = true;
      return;
    }

    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
  }

  private boolean checkDispatchMethodState(final List<Runnable> savedVisitations) {
    if (savedVisitations.size() != 4) {
      return false;
    }
    // push this to the stack
    if (!(savedVisitations.get(0) instanceof DelayCertainInsMethodVisitor.ALoadVarInsn)) {
      return false;
    }

    // push DispatcherType.REQUEST to the stack
    if (!(savedVisitations.get(1) instanceof DelayCertainInsMethodVisitor.GetStaticFieldInsn)) {
      return false;
    }
    DelayCertainInsMethodVisitor.GetStaticFieldInsn getStaticFieldInsn =
        (DelayCertainInsMethodVisitor.GetStaticFieldInsn) savedVisitations.get(1);
    if ((!getStaticFieldInsn.owner.equals("javax/servlet/DispatcherType")
            && !getStaticFieldInsn.owner.equals("jakarta/servlet/DispatcherType"))
        || !getStaticFieldInsn.name.equals("REQUEST")) {
      return false;
    }

    // push this to the stack (to create the lambda or access the instance field, which depends on
    // this)
    if (!(savedVisitations.get(2) instanceof DelayCertainInsMethodVisitor.ALoadVarInsn)) {
      return false;
    }

    final Runnable last = savedVisitations.get(3);
    // jetty < 11.16.0
    //  this.dispatch(DispatcherType.REQUEST, () -> { ... });
    if (last instanceof DelayCertainInsMethodVisitor.InvokeDynamicInsn) {
      return true;
    }

    // jetty >= 11.16.0
    // this.dispatch(DispatcherType.REQUEST, this._requestDispatcher);
    return last instanceof DelayCertainInsMethodVisitor.GetFieldInsn;
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
