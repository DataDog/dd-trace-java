package datadog.trace.instrumentation.couchbase.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.couchbase.client.core.message.CouchbaseRequest;
import com.couchbase.client.java.transcoder.crypto.JsonCryptoTranscoder;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class CouchbaseNetworkInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public CouchbaseNetworkInstrumentation() {
    super("couchbase");
  }

  static final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("com.couchbase.client.core.message.CouchbaseRequest");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // Exact class because private fields are used
    return nameStartsWith("com.couchbase.client.")
        .<TypeDescription>and(
            extendsClass(named("com.couchbase.client.core.endpoint.AbstractGenericHandler")));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.couchbase.client.core.message.CouchbaseRequest", AgentSpan.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // encode(ChannelHandlerContext ctx, REQUEST msg, List<Object> out)
    transformation.applyAdvice(
        isMethod()
            .and(named("encode"))
            .and(takesArguments(3))
            .and(
                takesArgument(
                    0, named("com.couchbase.client.deps.io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(2, named("java.util.List"))),
        CouchbaseNetworkInstrumentation.class.getName() + "$CouchbaseNetworkAdvice");
  }

  public static class CouchbaseNetworkAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addNetworkTagsToSpan(
        @Advice.FieldValue("remoteHostname") final String remoteHostname,
        @Advice.FieldValue("remoteSocket") final String remoteSocket,
        @Advice.FieldValue("localSocket") final String localSocket,
        @Advice.Argument(1) final CouchbaseRequest request) {
      final ContextStore<CouchbaseRequest, AgentSpan> contextStore =
          InstrumentationContext.get(CouchbaseRequest.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(request);
      if (span != null) {
        span.setTag(Tags.PEER_HOSTNAME, remoteHostname);

        if (remoteSocket != null) {
          final int splitIndex = remoteSocket.lastIndexOf(":");
          if (splitIndex != -1) {
            span.setTag(Tags.PEER_PORT, Integer.parseInt(remoteSocket.substring(splitIndex + 1)));
          }
        }

        span.setTag("local.address", localSocket);
      }
    }

    // 2.6.0 and above
    public static void muzzleCheck(final JsonCryptoTranscoder transcoder) {
      transcoder.documentType();
    }
  }
}
