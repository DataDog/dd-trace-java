package datadog.trace.instrumentation.jetty9;

import static net.bytebuddy.jar.asm.Opcodes.ALOAD;
import static net.bytebuddy.jar.asm.Opcodes.ASTORE;
import static net.bytebuddy.jar.asm.Opcodes.F_SAME;
import static net.bytebuddy.jar.asm.Opcodes.GOTO;
import static net.bytebuddy.jar.asm.Opcodes.H_INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.IFNE;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL;

import datadog.context.Context;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge;
import datadog.trace.instrumentation.jetty.JettyBlockingHelper;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
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
 * <pre>
 *   case REQUEST_DISPATCH:
 *   // ...
 *   getServer().handle(this);
 * </pre>
 * is replaced with:
 * <pre>
 *   case REQUEST_DISPATCH:
 *   // ...
 *   if (JettyBlockingHelper.block(this.getRequest(), this.getResponse(), Java8BytecodeBridge.getCurrentContext())) {
 *     // nothing
 *   } else {
 *     getServer().handle(this);
 *   }
 * </pre>
 * And for later versions of Jetty before 11.16.0,
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
 * is replaced with:
 * <pre>
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
 * </pre>
 * And for later versions of Jetty,
 * <pre>
 *   case DISPATCH:
 *   {
 *     // ...
 *     dispatch(DispatcherType.REQUEST, _requestDispatcher);
 * </pre>
 * is replaced with:
 * <pre>
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
 *       dispatch(DispatcherType.REQUEST, _requestDispatcher);
 *   }
 * </pre>
 */
public class HandleVisitor extends MethodVisitor {
  private static final Logger log = LoggerFactory.getLogger(HandleVisitor.class);
  private static final int CONTEXT_VAR = 1000;

  /** Whether the next store is supposed to store the Context variable. */
  private boolean lookForStore;
  /** Whether the Context variable was stored to local index {@link #CONTEXT_VAR}. */
  private boolean contextStored;
//  private int contextVarIndex = -1;
  /** Whether the handle() method injection was successful .*/
  private boolean success;
  private final String methodName;

  private BufferedWriter debugWriter;

  private void debug(String msg) {
    if (debugWriter == null) {
      return;
    }
    try {
      debugWriter.write(msg);
      debugWriter.newLine();
    } catch (IOException ignored) {
    }
  }

  public HandleVisitor(int api, DelayCertainInsMethodVisitor methodVisitor, String methodName) {
    super(api, methodVisitor);
    this.methodName = methodName;
    try {
      String path = "/Users/bruce.bujon/go/src/github.com/DataDog/dd-trace-java/bbujon/debug/HandleVisitor-" + System.nanoTime() + ".txt";
      this.debugWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path)));
      debug("Initializing");
    } catch (IOException ignored) {
    }
  }

  DelayCertainInsMethodVisitor delayVisitorDelegate() {
    return (DelayCertainInsMethodVisitor) this.mv;
  }

//  @Override
//  public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
//    if (contextStored && index == CONTEXT_VAR) {
//      super.visitLocalVariable("context", Type.getDescriptor(Context.class), null, start, end, CONTEXT_VAR);
//    }
//    super.visitLocalVariable(name, descriptor, signature, start, end, index);
//  }

  @Override
  public void visitMethodInsn(
      int opcode, String owner, String name, String descriptor, boolean isInterface) {
    debug("visitMethodInsn");
    debug(">> contextStored: " + contextStored);
    debug(">> success: " + success);
    debug(">> opcode: " + opcode + ", owner: " + owner + ", name: " + name + ", descriptor: " + descriptor);
    if (!contextStored) {
      lookForStore =
          !lookForStore
              && opcode == INVOKEVIRTUAL
              && name.equals("startSpan")
              && descriptor.endsWith("Ldatadog/context/Context;");
      if (lookForStore) {
        debug("Found store");
      }
    } else if (!success
        && opcode == INVOKEVIRTUAL
        && owner.equals("org/eclipse/jetty/server/Server")
        && name.equals("handle")
        && descriptor.equals("(Lorg/eclipse/jetty/server/HttpChannel;)V")) {
      debug("handle bytecode found");
      DelayCertainInsMethodVisitor mv = delayVisitorDelegate();
      List<Runnable> savedVisitations = mv.transferVisitations();
      /*
       * Saved visitations should be for:
       *
       * aload_0
       * invokevirtual #78                 // Method getServer:()Lorg/eclipse/jetty/server/Server;
       * aload_0
       */
      debug("Saved visitation size: "+savedVisitations.size());
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
          false
      );
//      super.visitVarInsn(ALOAD, CONTEXT_VAR);
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
      debug("handle bytecode injected");
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

      super.visitVarInsn(ALOAD, CONTEXT_VAR);
      super.visitJumpInsn(Opcodes.IFNULL, beforeRegularDispatch);
      super.visitVarInsn(ALOAD, CONTEXT_VAR);
      super.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          "datadog/trace/bootstrap/instrumentation/api/AgentSpan",
          "getRequestBlockingAction",
          "()" + Type.getDescriptor(Flow.Action.RequestBlockingAction.class),
          true);
      super.visitJumpInsn(Opcodes.IFNONNULL, doBlockLabel);
      super.visitJumpInsn(GOTO, beforeRegularDispatch);

      super.visitLabel(doBlockLabel);
      super.visitFrame(F_SAME, 0, null, 0, null);
      // dispatch with a Dispatchable created from JettyBlockingHelper::block
      // first set up the first two arguments to dispatch (this and DispatcherType.REQUEST)
      List<Runnable> loadThisAndEnum = new ArrayList<>(savedVisitations.subList(0, 2));
      mv.commitVisitations(loadThisAndEnum);
      // set up the arguments to the method underlying the lambda (Request, Response,
      // RequestBlockingAction, AgentSpan)
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
      super.visitVarInsn(ALOAD, CONTEXT_VAR);
      super.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          "datadog/trace/bootstrap/instrumentation/api/AgentSpan",
          "getRequestBlockingAction",
          "()" + Type.getDescriptor(Flow.Action.RequestBlockingAction.class),
          true);
      super.visitVarInsn(ALOAD, CONTEXT_VAR);

      // create the lambda
      super.visitInvokeDynamicInsn(
          "dispatch",
          "(Lorg/eclipse/jetty/server/Request;Lorg/eclipse/jetty/server/Response;"
              + Type.getDescriptor(Flow.Action.RequestBlockingAction.class)
              + Type.getDescriptor(AgentSpan.class)
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
                    + Type.getDescriptor(Flow.Action.RequestBlockingAction.class)
                    + Type.getDescriptor(AgentSpan.class)
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
  public void visitVarInsn(int opcode, int varIndex) {
    if (lookForStore && opcode == ASTORE) {
      debug("Found context");
      contextStored = true;
      lookForStore = false;
//      contextVarIndex = varIndex;
      // Duplicate on stack and store to its own local var
//      super.visitInsn(DUP);
//      super.visitVarInsn(ASTORE, CONTEXT_VAR);
    }
    super.visitVarInsn(opcode, varIndex);
  }

//  @Override
//  public void visitMaxs(int maxStack, int maxLocals) {
//    debug("VisitMaxs stack: " + maxStack + ", locals: " + maxLocals);
//    super.visitMaxs(maxStack, max(maxLocals, CONTEXT_VAR + 1));
//  }

  @Override
  public void visitEnd() {
    if (!success && !"run".equals(methodName)) {
      log.warn(
          "Transformation of Jetty's connection class was not successful. Blocking will likely not work");
    }
    if (this.debugWriter != null) {
      try {
        this.debugWriter.close();
      } catch (IOException ignored) {
      }
    }
    super.visitEnd();
  }
}
