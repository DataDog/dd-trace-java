package datadog.trace.agent.test.asserts

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.naming.SpanNaming
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.sampling.RateByServiceTraceSampler
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.util.regex.Pattern

class TagsAssert {
  private final long spanParentId
  private final Map<String, Object> tags
  private final Set<String> assertedTags = new TreeSet<>()

  private TagsAssert(DDSpan span) {
    this.spanParentId = span.parentId
    this.tags = span.tags
  }
  // DDSpan [ t_id=86, s_id=85, p_id=0 ]  trace=worker.org.gradle.process.internal.worker.GradleWorkerMain/http.request/GET /success *measured* tags={_dd.agent_psr=1.0, _dd.dsm.enabled=1, _dd.profiling.ctx=test, _dd.profiling.enabled=0, _dd.trace_span_attribute_schema=0, _dd.tracer_host=COMP-GHXRH1QQ7F, _sample_rate=1, component=apache-httpclient5, http.method=GET, http.status_code=200, http.url=http://localhost:54233/success, language=jvm, pathway.hash=14628910375923456673, peer.hostname=localhost, peer.port=54233, process_id=72497, request_header_tag=foo,bar,baz, runtime-id=a74ef0dc-8747-43f8-853c-02ddce42dcaa, span.kind=client, thread.id=1, thread.name=Test worker}, duration_ns=3604416, forceKeep=false, links=[]
  // DDSpan [ t_id=86, s_id=87, p_id=85 ] trace=worker.org.gradle.process.internal.worker.GradleWorkerMain/test-http-server/test-http-server    tags={_dd.dsm.enabled=1, _dd.profiling.ctx=test, _dd.profiling.enabled=0, _dd.trace_span_attribute_schema=0, _dd.tracer_host=COMP-GHXRH1QQ7F, _sample_rate=1, language=jvm, path=/success, process_id=72497, request_header_tag=foo,bar, runtime-id=a74ef0dc-8747-43f8-853c-02ddce42dcaa, span.kind=server, thread.id=27, thread.name=qtp897087270-27}, duration_ns=58625, forceKeep=false, links=[]

  static void assertTags(DDSpan span,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TagsAssert'])
    @DelegatesTo(value = TagsAssert, strategy = Closure.DELEGATE_FIRST) Closure spec,
    boolean checkAllTags = true) {
    System.out.println("=== SARAH: START SPAN ===")
    System.out.println(span.toString())
    System.out.println("=== SARAH: END SPAN   ===")
    def asserter = new TagsAssert(span)
    def clone = (Closure) spec.clone()
    clone.delegate = asserter
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(asserter)
    if (checkAllTags) {
      asserter.assertTagsAllVerified()
    }
  }

  /**
   * Check that, if the peer.service tag source has been set, it matches the provided one.
   * @param sourceTag the source to match
   */
  def peerServiceFrom(String sourceTag) {
    tag(DDTags.PEER_SERVICE_SOURCE, { SpanNaming.instance().namingSchema().peerService().supports() ? it == sourceTag : it == null })
  }

  def defaultTagsNoPeerService(boolean distributedRootSpan = false) {
    defaultTags(distributedRootSpan, false)
  }

  def isPresent(String name) {
    tag(name, { it != null })
  }


  /**
   * @param distributedRootSpan set to true if current span has a parent span but still considered 'root' for current service
   */
  def defaultTags(boolean distributedRootSpan = false, boolean checkPeerService = true) {
    assertedTags.add("thread.name")
    assertedTags.add("thread.id")
    assertedTags.add(DDTags.RUNTIME_ID_TAG)
    assertedTags.add(DDTags.LANGUAGE_TAG_KEY)
    assertedTags.add(RateByServiceTraceSampler.SAMPLING_AGENT_RATE)
    assertedTags.add(TraceMapper.SAMPLING_PRIORITY_KEY.toString())
    assertedTags.add("_sample_rate")
    assertedTags.add(DDTags.PID_TAG)
    assertedTags.add(DDTags.SCHEMA_VERSION_TAG_KEY)
    assertedTags.add(DDTags.PROFILING_ENABLED)
    assertedTags.add(DDTags.PROFILING_CONTEXT_ENGINE)
    assertedTags.add(DDTags.BASE_SERVICE)
    assertedTags.add(DDTags.DSM_ENABLED)
    assertedTags.add(DDTags.DJM_ENABLED)
    assertedTags.add(DDTags.PARENT_ID)

    assert tags["thread.name"] != null
    assert tags["thread.id"] != null

    // FIXME: DQH - Too much conditional logic?  Maybe create specialized methods for client & server cases

    boolean isRoot = (DDSpanId.ZERO == spanParentId)
    if (isRoot) {
      assert tags[DDTags.SCHEMA_VERSION_TAG_KEY] == SpanNaming.instance().version()
    }
    if (isRoot || distributedRootSpan) {
      // If runtime id is actually different here, it might indicate that
      // the Config class was loaded on multiple different class loaders.
      assert tags[DDTags.RUNTIME_ID_TAG] == Config.get().runtimeId
      assertedTags.add(DDTags.TRACER_HOST)
      assert tags[DDTags.TRACER_HOST] == Config.get().getHostName()
    } else {
      assert tags[DDTags.RUNTIME_ID_TAG] == null
    }
    String spanKind = tags[Tags.SPAN_KIND]
    boolean isServer = (spanKind == Tags.SPAN_KIND_SERVER)
    if (isRoot || distributedRootSpan || isServer) {
      assert tags[DDTags.LANGUAGE_TAG_KEY] == DDTags.LANGUAGE_TAG_VALUE
    } else {
      assert tags[DDTags.LANGUAGE_TAG_KEY] == null
    }
    boolean shouldSetPeerService = checkPeerService && (spanKind == Tags.SPAN_KIND_CLIENT || spanKind == Tags.SPAN_KIND_PRODUCER)
    if (shouldSetPeerService && SpanNaming.instance().namingSchema().peerService().supports()) {
      assertedTags.add(Tags.PEER_SERVICE)
      assertedTags.add(DDTags.PEER_SERVICE_SOURCE)
      assert tags[Tags.PEER_SERVICE] != null
      assert tags[Tags.PEER_SERVICE] == tags[tags[DDTags.PEER_SERVICE_SOURCE]]
    } else {
      assert tags[Tags.PEER_SERVICE] == null
      assert tags[DDTags.PEER_SERVICE_SOURCE] == null
    }
  }

  def errorTags(Throwable error) {
    errorTags(error.getClass(), error.getMessage())
  }

  def errorTags(Class<Throwable> errorType) {
    errorTags(errorType, null)
  }

  def errorTags(Class<Throwable> errorType, message) {
    tag("error.type", {
      if (it == errorType.name) {
        return true
      }
      try {
        // also accept type names which are sub-classes of the given error type
        return errorType.isAssignableFrom(
          Class.forName(it as String, false, getClass().getClassLoader()))
      } catch (Throwable ignore) {
        return false
      }
    })
    tag("error.stack", String)

    if (message != null) {
      tag("error.message", message)
    }
  }

  def urlTags(String url, List<String> queryParams){
    tag("http.url", {
      URI uri = new URI(it.toString().split("\\?", 2)[0])
      String scheme = uri.getScheme()
      String host = uri.getHost()
      int port = uri.getPort()
      String path = uri.getPath()

      String baseURL = scheme + "://" + host + ":" + port + path
      return baseURL.equals(url)
    })

    tag("http.query.string", {
      String paramString = it
      System.out.println("it: " + it)
      Set<String> spanQueryParams = new HashSet<String>()
      if (paramString != null && !paramString.isEmpty()) {
        String[] pairs = paramString.split("&")
        for (String pair : pairs) {
          int idx = pair.indexOf("=")
          if (idx > 0) {
            spanQueryParams.add(pair.substring(0, idx))
          } else {
            spanQueryParams.add(pair)
          }
        }
        for(String param : queryParams){
          if (!spanQueryParams.contains(param)){
            System.out.println("param: " + param)
            return false
          }
        }
      } else if(queryParams != null && queryParams.size() > 0){ //if http.query.string is empty/null but we expect queryParams, return false
        return false
      }
      return true
    })
  }

  def tag(String name, expected) {
    if (expected == null) {
      return
    }
    assertedTags.add(name)
    def value = tag(name)
    if (expected instanceof Pattern) {
      assert value =~ expected: "Tag \"$name\": \"${value.toString()}\" does not match pattern \"$expected\""
    } else if (expected instanceof Class) {
      assert ((Class) expected).isInstance(value): "Tag \"$name\": instance check $expected failed for \"${value.toString()}\" of class \"${value.class}\""
    } else if (expected instanceof Closure) {
      assert ((Closure) expected).call(value): "Tag \"$name\": closure call ${expected.toString()} failed with \"$value\""
    } else if (expected instanceof CharSequence) {
      System.out.println("SARAH TAGASSERT VALUE: " + value) // wrong tag value here
      assert value == expected.toString(): "Tag \"$name\": \"$value\" != \"${expected.toString()}\""
    } else {
      assert value == expected: "Tag \"$name\": \"$value\" != \"$expected\""
    }
  }

  def tag(String name) {
    def t = tags[name]
    return (t instanceof CharSequence) ? t.toString() : t
  }

  def methodMissing(String name, args) {
    if (args.length == 0) {
      throw new IllegalArgumentException(args.toString())
    }
    tag(name, args[0])
  }

  def addTags(Map<String, Serializable> tags) {
    System.out.println("======= SARAH: TAGS START =======")
    tags.each {
      System.out.println("CHECK THAT KEY " + it.key + " HAS VALUE " + it.value)
      tag(it.key, it.value)
      System.out.println("======= SARAH: TAGS END   =======")
    }
    true
  }

  void assertTagsAllVerified() {
    def set = new TreeMap<>(tags).keySet()
    set.removeAll(assertedTags)
    // The primary goal is to ensure the set is empty.
    // tags and assertedTags are included via an "always true" comparison
    // so they provide better context in the error message.
    assert tags.entrySet() != assertedTags && set.isEmpty()
  }
}
