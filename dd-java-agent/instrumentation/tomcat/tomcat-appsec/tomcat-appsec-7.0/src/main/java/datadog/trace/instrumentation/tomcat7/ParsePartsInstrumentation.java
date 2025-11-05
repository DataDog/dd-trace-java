package datadog.trace.instrumentation.tomcat7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
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
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

@AutoService(InstrumenterModule.class)
public class ParsePartsInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {

  public ParsePartsInstrumentation() {
    super("tomcat");
  }

  @Override
  public String muzzleDirective() {
    return "from7";
  }

  @Override
  public String instrumentedType() {
    return "org.apache.catalina.connector.Request";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.tomcat7.ParameterCollector",
      "datadog.trace.instrumentation.tomcat7.ParameterCollector$ParameterCollectorNoop",
      "datadog.trace.instrumentation.tomcat7.ParameterCollector$ParameterCollectorImpl",
    };
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new ParsePartsVisitorWrapper());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("parseParts")
            .and(takesArguments(0).or(takesArguments(1).and(takesArgument(0, boolean.class))))
            .and(isPrivate()),
        getClass().getName() + "$ParsePartsAdvice");
  }

  public static class ParsePartsAdvice {
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
      if (t != null || reqCtx == null) {
        return;
      }

      if (collector.isEmpty()) {
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
          blockResponseFunction.tryCommitBlockingResponse(
              reqCtx.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
          if (t == null) {
            t = new BlockingException("Blocked request (for Request/parseParts)");
          }
        }
      }
    }
  }

  public static class ParsePartsVisitorWrapper implements AsmVisitorWrapper {
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
      if ("parseParts".equals(name) && "()V".equals(descriptor) || "(Z)V".equals(descriptor)) {
        return new ParsePartsMethodVisitor(api, superMv, "(Z)V".equals(descriptor) ? 2 : 1);
      } else {
        return superMv;
      }
    }
  }

  public static class ParsePartsMethodVisitor extends MethodVisitor {
    private final int collectedParamsVar;

    public ParsePartsMethodVisitor(int api, MethodVisitor superMv, int collectedParamsVar) {
      super(api, superMv);
      this.collectedParamsVar = collectedParamsVar;
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if (opcode == Opcodes.INVOKEVIRTUAL
          && owner.equals("org/apache/tomcat/util/http/Parameters")
          && name.equals("addParameter")
          && descriptor.equals("(Ljava/lang/String;Ljava/lang/String;)V")) {
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
      } else if (opcode == Opcodes.INVOKEVIRTUAL
          && owner.equals("org/apache/tomcat/util/http/Parameters")
          && name.equals("addParameterValues")
          && descriptor.equals("(Ljava/lang/String;[Ljava/lang/String;)V")) {
        super.visitVarInsn(Opcodes.ALOAD, collectedParamsVar);
        super.visitInsn(Opcodes.DUP_X2);
        super.visitInsn(Opcodes.POP);
        super.visitInsn(Opcodes.DUP2_X1);
        super.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            Type.getInternalName(ParameterCollector.class),
            "put",
            "(Ljava/lang/String;[Ljava/lang/String;)V",
            true);
      }
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }
  }
}
