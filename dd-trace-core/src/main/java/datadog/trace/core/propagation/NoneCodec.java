package datadog.trace.core.propagation;

import static java.util.Collections.emptySet;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.ContextKey;
import java.util.Set;

public class NoneCodec {

  public static final HttpCodec.Injector INJECTOR =
      new HttpCodec.Injector() {
        @Override
        public <C> void inject(
            AgentScopeContext context, C carrier, AgentPropagation.Setter<C> setter) {}
      };

  public static final HttpCodec.Extractor EXTRACTOR =
      new HttpCodec.Extractor() {
        @Override
        public Set<ContextKey<?>> supportedContent() {
          return emptySet();
        }

        @Override
        public <C> void extract(
            HttpCodec.ScopeContextBuilder builder,
            C carrier,
            AgentPropagation.ContextVisitor<C> getter) {}
      };
}
