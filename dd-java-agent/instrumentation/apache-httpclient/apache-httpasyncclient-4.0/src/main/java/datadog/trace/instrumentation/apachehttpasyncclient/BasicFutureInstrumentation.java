package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;

public final class BasicFutureInstrumentation
    implements Instrumenter.ForSingleType,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.apache.http.concurrent.BasicFuture";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("callback"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$StealCallback");
  }

  // TODO there are numerous cases of using context stores to access immutable private fields
  //  where the field value is copied into a context field so it can be accessed via
  //  instrumentation context (e.g. MongoDB), but this has a footprint cost and can't be used
  //  for mutable fields. This mechanism could be enhanced to direct ContextStore.get to a
  //  generated field accessor, which would eliminate both these concerns.
  public static final class StealCallback {
    @Advice.OnMethodExit
    public static <T> void postConstruct(
        @Advice.This BasicFuture<T> future,
        @Advice.FieldValue("callback") FutureCallback<T> callback) {
      // the callback can now be accessed elsewhere in the processing pipeline
      InstrumentationContext.get(BasicFuture.class, FutureCallback.class).put(future, callback);
    }
  }
}
