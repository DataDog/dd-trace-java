package datadog.trace.instrumentation.vertx_3_4.server;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import java.util.List;
import java.util.regex.Matcher;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

@AutoService(Instrumenter.class)
public class RouteImplInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public RouteImplInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher.ConjunctionReferenceMatcher(
        origMatcher, VertxVersionMatcher.INSTANCE);
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RouteImpl";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PathParameterPublishingHelper",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {}

  @Override
  public AdviceTransformer transformer() {
    return new AdviceTransformer() {
      @Override
      public DynamicType.Builder<?> transform(
          DynamicType.Builder<?> builder,
          TypeDescription typeDescription,
          ClassLoader classLoader,
          JavaModule module) {
        return builder.visit(new RouteMatchingVisitor());
      }
    };
  }

  public static class RouteMatchingVisitor implements AsmVisitorWrapper {

    @Override
    public int mergeWriter(int flags) {
      return flags | ClassWriter.COMPUTE_MAXS;
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

      return new ClassVisitor(Opcodes.ASM8, classVisitor) {
        @Override
        public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
          MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
          if (name.equals("matches")
              && descriptor.equals("(Lio/vertx/ext/web/RoutingContext;Ljava/lang/String;Z)Z")) {
            return new MatchesMethodVisitor(parent);
          }
          return parent;
        }
      };
    }
  }

  /**
   * Looks for:
   *
   * <pre>
   * if (m.groupCount() > 0) {
   *   Map<String, String> params = new HashMap<>(m.groupCount());
   * </pre>
   *
   * and inserts a call to <code>PathParameterPublishingHelper.publishParams(m, this.groups)</code>.
   *
   * <p>Before the first call to groupCount, it dups the top of the stack, <code>m</code>. After
   * this first call to groupCount(), the stack will have (matcher, int). It then changes the target
   * of the jump for when m.groupCount() <= 0 (IFLE) to a place where the matcher atop the stack is
   * dropped and a jump is made to the original location.
   *
   * <p>If the jump is not made, then, just before the new HashMap is allocated, the groups field is
   * fetched, leaving the stack with (matcher, groups). These are the arguments for {@link
   * PathParameterPublishingHelper#publishParams(Matcher, List)}). The call is made, consuming the
   * two elements of the stack and restoring the state expected by the original code.
   */
  public static class MatchesMethodVisitor extends MethodVisitor {
    enum State {
      INITIAL,
      FIRST_GROUP_COUNT_FOUND,
      IFLE_FOUND,
      AFTER_HELPER_CALL;
    }

    State state = State.INITIAL;
    Label dropMatcherLabel = new Label();
    Label originalJumpLabel = new Label();

    public MatchesMethodVisitor(MethodVisitor methodVisitor) {
      super(Opcodes.ASM8, methodVisitor);
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {

      if (state == State.INITIAL
          && owner.equals("java/util/regex/Matcher")
          && name.equals("groupCount")
          && descriptor.equals("()I")) {
        state = State.FIRST_GROUP_COUNT_FOUND;

        // put another copy of the matcher on the stack
        mv.visitInsn(Opcodes.DUP); // matcher, matcher
      }

      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      if (state == State.FIRST_GROUP_COUNT_FOUND) {
        if (opcode == Opcodes.IFLE) {
          originalJumpLabel = label;
          state = State.IFLE_FOUND;
          super.visitJumpInsn(opcode, dropMatcherLabel);
        } else {
          throw new RuntimeException(
              "unexpected contents for implementation of RouteImpl::match: " + opcode);
        }
      } else {
        super.visitJumpInsn(opcode, label);
      }
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      if (state == State.IFLE_FOUND && opcode == Opcodes.NEW && type.equals("java/util/HashMap")) {

        mv.visitVarInsn(Opcodes.ALOAD, 0); // matcher, this
        mv.visitFieldInsn(
            Opcodes.GETFIELD, "io/vertx/ext/web/impl/RouteImpl", "groups", "Ljava/util/List;");
        // matcher, list of groups
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "datadog/trace/instrumentation/vertx_3_4/server/PathParameterPublishingHelper",
            "publishParams",
            "(Ljava/util/regex/Matcher;Ljava/util/List;)V",
            false);

        state = State.AFTER_HELPER_CALL;
      }

      super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitEnd() {
      if (state != State.AFTER_HELPER_CALL) {
        throw new RuntimeException(
            "unexpected contents for implementation of RouteImpl::match; not finished");
      }

      mv.visitLabel(dropMatcherLabel);
      mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/util/regex/Matcher"});
      mv.visitInsn(Opcodes.POP);
      mv.visitJumpInsn(Opcodes.GOTO, originalJumpLabel);
    }
  }
}
