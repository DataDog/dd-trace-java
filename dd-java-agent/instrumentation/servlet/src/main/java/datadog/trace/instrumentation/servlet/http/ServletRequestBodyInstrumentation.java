package datadog.trace.instrumentation.servlet.http;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.http.StoredByteBody;
import datadog.trace.api.http.StoredCharBody;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.security.ProtectionDomain;
import java.security.SecureClassLoader;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

@AutoService(Instrumenter.class)
public class ServletRequestBodyInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForTypeHierarchy {
  public ServletRequestBodyInstrumentation() {
    super("servlet-request-body");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.ServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getInputStream")
            .and(takesNoArguments())
            .and(returns(named("javax.servlet.ServletInputStream")))
            .and(isPublic()),
        getClass().getName() + "$HttpServletGetInputStreamAdvice");
    transformation.applyAdvice(
        named("getReader")
            .and(takesNoArguments())
            .and(returns(named("java.io.BufferedReader")))
            .and(isPublic()),
        getClass().getName() + "$HttpServletGetReaderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".BufferedReaderWrapper"};
  }

  @Override
  public String[] muzzleIgnoredClassNames() {
    String[] initial = super.muzzleIgnoredClassNames();
    String[] ret = new String[initial.length + 1];
    System.arraycopy(initial, 0, ret, 0, initial.length);
    ret[initial.length] = packageName + ".ServletInputStreamWrapper";
    return ret;
  }

  private static final ClassLoader BOOTSTRAP_CLASSLOADER_PLACEHOLDER =
      new SecureClassLoader(null) {
        @Override
        public String toString() {
          return "<bootstrap>";
        }
      };

  @Override
  public AdviceTransformer transformer() {
    return new CustomTransformer();
  }

  // transformer possible adding extra 3 methods to ServletInputStreamWrapper
  static class CustomTransformer implements AdviceTransformer {
    private static final String STREAM_WRAPPER_TYPE =
        "datadog.trace.instrumentation.servlet.http.ServletInputStreamWrapper";
    private final Map<ClassLoader, Boolean> injectedClassLoaders =
        Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());

    @Override
    public DynamicType.Builder<?> transform(
        DynamicType.Builder<?> builder,
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        ProtectionDomain pd) {
      if (classLoader == null) {
        classLoader = BOOTSTRAP_CLASSLOADER_PLACEHOLDER;
      }

      if (injectedClassLoaders.containsKey(classLoader)) {
        return builder;
      }
      injectedClassLoaders.put(classLoader, Boolean.TRUE);

      TypePool.Resolution readListenerRes;
      TypePool typePoolUserCl;
      if (classLoader != BOOTSTRAP_CLASSLOADER_PLACEHOLDER) {
        typePoolUserCl = TypePool.Default.of(classLoader);
      } else {
        typePoolUserCl = TypePool.Default.ofBootLoader();
      }
      readListenerRes = typePoolUserCl.describe("javax.servlet.ReadListener");
      if (!readListenerRes.isResolved()) {
        // likely servlet < 3.1
        // inject original
        return new HelperInjector("servlet-request-body", STREAM_WRAPPER_TYPE)
            .transform(builder, typeDescription, classLoader, module, pd);
      }

      // else at the very least servlet 3.1+ classes are available
      // modify ServletInputStreamWrapper before injecting it. This should be harmless even if
      // servlet 3.1 is on the
      // classpath without the implementation supporting it
      ClassFileLocator compoundLocator =
          new ClassFileLocator.Compound(
              ClassFileLocator.ForClassLoader.of(getClass().getClassLoader()),
              ClassFileLocator.ForClassLoader.of(classLoader));

      TypePool.Resolution origWrapperRes =
          TypePool.Default.of(compoundLocator).describe(STREAM_WRAPPER_TYPE);
      if (!origWrapperRes.isResolved()) {
        throw new RuntimeException("Could not load original ServletInputStreamWrapper");
      }
      TypeDescription origWrapperType = origWrapperRes.resolve();

      DynamicType.Unloaded<?> unloaded =
          new ByteBuddy()
              .rebase(origWrapperType, compoundLocator)
              .method(named("isFinished").and(takesNoArguments()))
              .intercept(MethodDelegation.toField("is"))
              .method(named("isReady").and(takesNoArguments()))
              .intercept(MethodDelegation.toField("is"))
              .method(named("setReadListener").and(takesArguments(readListenerRes.resolve())))
              .intercept(MethodDelegation.toField("is"))
              .make();
      return new HelperInjector(
              "servlet-request-body",
              Collections.singletonMap(origWrapperType.getName(), unloaded.getBytes()))
          .transform(builder, typeDescription, classLoader, module, pd);
    }
  }

  @SuppressWarnings("Duplicates")
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class HttpServletGetInputStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final ServletRequest thiz,
        @Advice.Return(readOnly = false) ServletInputStream is,
        @ActiveRequestContext RequestContext reqCtx) {
      if (!(thiz instanceof HttpServletRequest) || is == null) {
        return;
      }

      HttpServletRequest req = (HttpServletRequest) thiz;
      Object alreadyWrapped = req.getAttribute("datadog.wrapped_request_body");
      if (alreadyWrapped != null || is instanceof ServletInputStreamWrapper) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
          cbp.getCallback(EVENTS.requestBodyStart());
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
          cbp.getCallback(EVENTS.requestBodyDone());
      if (requestStartCb == null || requestEndedCb == null) {
        return;
      }

      req.setAttribute("datadog.wrapped_request_body", Boolean.TRUE);

      int lengthHint = 0;
      String lengthHeader = req.getHeader("content-length");
      if (lengthHeader != null) {
        try {
          lengthHint = Integer.parseInt(lengthHeader);
        } catch (NumberFormatException nfe) {
          // purposefully left blank
        }
      }

      String encoding = req.getCharacterEncoding();
      Charset charset = null;
      try {
        if (encoding != null) {
          charset = Charset.forName(encoding);
        }
      } catch (IllegalArgumentException iae) {
        // purposefully left blank
      }

      StoredByteBody storedByteBody =
          new StoredByteBody(reqCtx, requestStartCb, requestEndedCb, charset, lengthHint);
      ServletInputStreamWrapper servletInputStreamWrapper =
          new ServletInputStreamWrapper(is, storedByteBody);

      is = servletInputStreamWrapper;
    }
  }

  @SuppressWarnings("Duplicates")
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class HttpServletGetReaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This final ServletRequest thiz,
        @Advice.Return(readOnly = false) BufferedReader reader) {
      if (!(thiz instanceof HttpServletRequest) || reader == null) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }
      HttpServletRequest req = (HttpServletRequest) thiz;
      Object alreadyWrapped = req.getAttribute("datadog.wrapped_request_body");
      if (alreadyWrapped != null || reader instanceof BufferedReaderWrapper) {
        return;
      }
      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext == null) {
        return;
      }
      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
          cbp.getCallback(EVENTS.requestBodyStart());
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
          cbp.getCallback(EVENTS.requestBodyDone());
      if (requestStartCb == null || requestEndedCb == null) {
        return;
      }

      req.setAttribute("datadog.wrapped_request_body", Boolean.TRUE);

      int lengthHint = 0;
      String lengthHeader = req.getHeader("content-length");
      if (lengthHeader != null) {
        try {
          lengthHint = Integer.parseInt(lengthHeader);
        } catch (NumberFormatException nfe) {
          // purposefully left blank
        }
      }

      StoredCharBody storedCharBody =
          new StoredCharBody(requestContext, requestStartCb, requestEndedCb, lengthHint);
      reader = new BufferedReaderWrapper(reader, storedCharBody);
    }
  }
}
