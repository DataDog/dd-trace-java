package datadog.trace.instrumentation.jetty8;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiFunction;
import javax.servlet.ServletException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import org.eclipse.jetty.server.Request;

@AutoService(InstrumenterModule.class)
public class RequestGetPartsInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {
  public RequestGetPartsInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ParameterCollector",
      packageName + ".ParameterCollector$ParameterCollectorImpl",
      packageName + ".ParameterCollector$ParameterCollectorNoop",
    };
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new GetPartsVisitorWrapper());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getPart")
            .and(takesArguments(1))
            .and(takesArgument(0, String.class))
            .or(named("getParts").and(takesArguments(0))),
        getClass().getName() + "$GetPartsAdvice");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return RequestImplementationClassLoaderMatcher.INSTANCE;
  }

  public static class RequestImplementationClassLoaderMatcher
      extends ElementMatcher.Junction.ForNonNullValues<ClassLoader> {
    public static final ElementMatcher.Junction<ClassLoader> INSTANCE =
        new RequestImplementationClassLoaderMatcher();

    @Override
    protected boolean doMatch(ClassLoader cl) {
      try (InputStream is = cl.getResourceAsStream("org/eclipse/jetty/server/Request.class")) {
        if (is == null) {
          return false;
        }
        ClassReader classReader = new ClassReader(is);
        final boolean[] foundField = new boolean[1];
        final boolean[] foundGetParameters = new boolean[1];
        classReader.accept(new ClassLoaderMatcherClassVisitor(foundField, foundGetParameters), 0);
        return !foundField[0] && foundGetParameters[0];
      } catch (IOException e) {
        return false;
      }
    }
  }

  public static class ClassLoaderMatcherClassVisitor extends ClassVisitor {
    final boolean[] foundField;
    final boolean[] foundGetParameters;

    public ClassLoaderMatcherClassVisitor(boolean[] foundField, boolean[] foundGetParameters) {
      super(Opcodes.ASM9);
      this.foundField = foundField;
      this.foundGetParameters = foundGetParameters;
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      if (name.equals("_contentParameters")) {
        foundField[0] = true;
      }
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if (name.equals("getParts") && "()Ljava/util/Collection;".equals(descriptor)) {
        return new MethodVisitor(Opcodes.ASM9) {
          @Override
          public void visitMethodInsn(
              int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                && name.equals("getParameters")
                && descriptor.equals("()Lorg/eclipse/jetty/util/MultiMap;")) {
              foundGetParameters[0] = true;
            }
          }
        };
      }
      return null;
    }
  }

  public static class GetPartsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(
        @Advice.Local("collector") ParameterCollector collector,
        @Advice.Local("reqCtx") RequestContext reqCtx) {
      AgentSpan agentSpan = AgentTracer.activeSpan();
      if (agentSpan != null) {
        RequestContext requestContext = agentSpan.getRequestContext();
        if (requestContext != null && requestContext.getData(RequestContextSlot.APPSEC) != null) {
          reqCtx = requestContext;
          collector = new ParameterCollector.ParameterCollectorImpl();
          return;
        }
      }
      // this variable is used in the custom instrumentation below
      collector = ParameterCollector.ParameterCollectorNoop.INSTANCE;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Local("collector") ParameterCollector collector,
        @Advice.Local("reqCtx") RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null || reqCtx == null || collector.isEmpty()) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }
      Flow<Void> flow = callback.apply(reqCtx, collector.getMap());
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
          if (t == null) {
            t = new BlockingException("Blocked request (for Request/parsePart(s))");
          }
        }
      }
    }

    static void muzzle(Request req) throws ServletException, IOException {
      req.getParts();
    }
  }

  public static class GetPartsVisitorWrapper implements AsmVisitorWrapper {
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
      return new RequestClassVisitor(Opcodes.ASM8, classVisitor);
    }
  }

  public static class RequestClassVisitor extends ClassVisitor {
    public RequestClassVisitor(int api, ClassVisitor cv) {
      super(api, cv);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor superMv = super.visitMethod(access, name, descriptor, signature, exceptions);
      if ("getPart".equals(name)
              && "(Ljava/lang/String;)Ljavax/servlet/http/Part;".equals(descriptor)
          || "getParts".equals(name) && "()Ljava/util/Collection;".equals(descriptor)) {
        return new GetPartsMethodVisitor(api, superMv, descriptor.startsWith("()") ? 1 : 2);
      } else {
        return superMv;
      }
    }
  }

  public static class GetPartsMethodVisitor extends MethodVisitor {
    private final int collectedParamsVar;

    public GetPartsMethodVisitor(int api, MethodVisitor superMv, int collectedParamsVar) {
      super(api, superMv);
      this.collectedParamsVar = collectedParamsVar;
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if (opcode == Opcodes.INVOKEVIRTUAL
          && owner.equals("org/eclipse/jetty/util/MultiMap")
          && name.equals("add")
          && descriptor.equals("(Ljava/lang/String;Ljava/lang/Object;)V")) {
        super.visitVarInsn(Opcodes.ALOAD, collectedParamsVar);
        // stack: ..., key, value, collParams
        super.visitInsn(Opcodes.DUP_X2);
        // stack: ..., collParams, key, value, collParams
        super.visitInsn(Opcodes.POP);
        // stack: ..., collParams, key, value
        super.visitInsn(Opcodes.DUP2_X1);
        // stack: ..., key, value, collParams, key, value
        super.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            Type.getInternalName(ParameterCollector.class),
            "put",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            true);
        // original stack
      }
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }
  }
}
