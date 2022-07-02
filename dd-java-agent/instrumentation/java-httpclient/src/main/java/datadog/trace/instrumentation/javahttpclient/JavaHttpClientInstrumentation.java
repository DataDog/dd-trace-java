package datadog.trace.instrumentation.javahttpclient;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(Instrumenter.class)
public class JavaHttpClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {

  private static final Logger LOGGER = LoggerFactory.getLogger(JavaHttpClientInstrumentation.class);

  public JavaHttpClientInstrumentation() {
    super("httpclient", "java-httpclient", "java-http-client");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[]{
        "java.net.http.HttpClient"
    };
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("java.net.")
        .or(nameStartsWith("jdk.internal."))
        .and(not(named("jdk.internal.net.http.HttpClientFacade")))
        .and(extendsClass(named("java.net.http.HttpClient")));
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".JavaHttpClientDecorator",
        packageName + ".JavaHttpClientAdvice",
        packageName + ".JavaHttpClientAdvice$SendAdvice",
        packageName + ".JavaHttpHeadersAdvice",
        packageName + ".JavaHttpHeadersAdvice$HeadersAdvice"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    LOGGER.info("Applying advice transformations");
    transformation.applyAdvice(
        isMethod().and(named("headers")),
        packageName + ".JavaHttpHeadersAdvice$HeadersAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("send"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("java.net.http.HttpRequest"))),
        packageName + ".JavaHttpClientAdvice$SendAdvice");
  }
}
