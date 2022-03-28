package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.api.ToIntFunction;
import datadog.trace.util.StringTrie;

// Generated from 'ignores.trie' - DO NOT EDIT!
public class IgnoresTrie extends StringTrie {
  private static final String TRIE_DATA =
      "\011\141\143\144\151\152\153\156\157\163\001\000\133\141\000\161\146\164\000\173\u0195\u01ab\u01b3\u01ec"
          + "\u01ed\u01f5\u0435\003\141\150\163\002\011\026\041\122\001\056\u4002\004\101\103\114\123\002\006\007"
          + "\010\017\020\021\002\103\123\003\004\001\u8000\002\044\111\000\005\001\u8000\u8000\u8000\u8000\u8000"
          + "\002\151\163\012\022\035\001\056\u4002\002\145\165\013\021\023\003\143\150\163\014\017\020\010\011"
          + "\002\120\160\015\016\001\u8000\u8000\u8000\u8000\u8000\001\056\u4002\001\110\023\002\062\105\024\025"
          + "\001\u8000\u8000\001\056\u4002\002\151\163\027\034\017\002\106\146\030\033\010\002\111\117\031\032"
          + "\001\u8000\u8000\u8000\002\107\124\035\036\001\u8000\u8000\004\150\151\154\157\037\044\045\000\025"
          + "\026\027\001\056\u4002\001\143\000\002\154\157\040\043\010\002\114\163\041\042\001\u8000\u8000\u8000"
          + "\u8001\u8001\002\056\155\046\047\001\u8001\015\141\142\143\144\146\147\151\152\154\155\156\160\163"
          + "\050\051\000\063\064\000\120\000\124\125\126\127\000\001\002\057\060\076\246\247\263\264\265\266\267"
          + "\u8001\u8002\002\141\157\052\000\007\001\056\u4002\001\110\053\u8000\003\144\156\165\054\056\057\007"
          + "\010\001\056\u4002\001\163\055\u8000\u8001\001\056\u4002\003\143\151\157\060\061\062\001\002\u8000"
          + "\u8000\u8000\u8001\002\143\152\065\066\001\u8002\001\056\u4002\001\155\067\u8000\002\151\157\070\071"
          + "\001\u8002\011\141\143\147\151\152\154\160\162\164\072\000\100\101\110\111\114\115\000\007\031\032"
          + "\063\064\074\075\076\001\056\u4002\001\143\073\u8000\002\154\157\074\075\001\u8002\001\056\u4002\002"
          + "\142\165\076\077\001\u8000\u8000\u8002\002\152\163\102\107\022\001\056\u4002\001\151\103\003\101\102"
          + "\143\104\105\106\001\002\u8000\u8000\u8000\u8002\u8002\002\147\156\112\113\001\u8002\u8002\u8002\u8002"
          + "\002\150\171\116\117\001\u8002\u8002\u8001\003\141\151\154\121\122\123\001\002\u8002\u8001\u8001\u8002"
          + "\u8003\u8001\u8001\002\151\165\130\101\001\u8001\001\056\u4001\002\152\155\131\132\001\u8000\u8000"
          + "\003\157\163\164\134\135\136\001\002\u8001\u8001\001\056\u4001\002\142\143\137\140\001\u8000\u8001"
          + "\002\155\156\142\143\001\u8001\u8001\002\141\144\144\160\062\002\056\170\u4001\047\044\004\154\156"
          + "\162\165\145\146\151\152\001\011\012\u8000\002\110\125\147\150\001\u8000\u8000\u8000\002\143\154\153"
          + "\154\001\u8000\001\056\u4000\001\114\155\u8001\002\145\170\156\157\001\u8002\u8002\u8001\u8002\002"
          + "\142\163\162\163\001\u8001\u8002\011\141\143\145\147\150\152\163\170\171\000\204\206\170\211\000\225"
          + "\157\342\117\126\136\137\226\236\u0223\u0224\002\160\163\165\203\110\011\142\146\147\150\154\162\164"
          + "\167\170\166\167\170\171\000\176\177\157\000\001\002\003\004\032\033\034\035\u8002\u8001\u8001\u8002"
          + "\002\157\165\172\175\017\001\056\u4002\003\103\115\163\173\174\042\001\002\u8000\u8000\u8000\u8002"
          + "\u8002\u8002\u8002\004\141\145\155\160\200\201\156\202\001\002\003\u8002\u8002\u8002\u8002\u8001\001"
          + "\056\u4001\001\162\205\u8000\002\146\151\207\210\001\u8001\u8001\u8001\001\056\u4002\006\104\145\152"
          + "\163\164\165\212\013\215\216\221\152\001\011\021\031\032\u8000\002\104\117\213\214\001\u8000\u8000"
          + "\002\056\170\000\047\001\u8000\u8000\002\106\127\217\220\001\u8000\u8000\u8000\002\115\124\222\223"
          + "\001\u8000\u8000\002\151\163\122\224\001\u8001\u8002\001\056\u4000\022\141\142\143\144\145\146\150"
          + "\151\152\154\155\157\162\163\164\165\166\167\000\000\000\300\000\306\023\311\000\321\132\000\324\000"
          + "\327\000\331\332\016\173\263\311\321\322\335\353\u0108\u0109\u010a\u0112\u0113\u011b\u011c\u012a\u012b"
          + "\002\155\157\226\227\001\u8000\001\056\u4002\001\151\230\u8000\002\145\157\231\242\043\001\056\u4002"
          + "\001\146\232\002\147\163\233\234\001\u8000\002\101\104\235\000\010\002\101\102\236\237\001\u8000\u8000"
          + "\002\145\151\240\241\001\u8000\u8000\001\056\u4002\003\141\143\167\243\246\254\010\033\002\102\143"
          + "\244\245\001\u8000\u8000\003\101\105\164\247\250\251\001\002\u8000\u8000\002\123\127\252\253\001\u8000"
          + "\u8000\002\145\163\255\262\017\002\156\164\256\257\001\u8000\002\105\127\260\261\001\u8000\u8000\002"
          + "\101\123\263\264\001\u8000\u8000\003\141\147\157\265\266\000\001\010\u8002\001\056\u4002\001\143\267"
          + "\u8000\002\156\162\270\273\016\001\056\u4002\001\163\234\002\101\103\271\272\001\u8000\u8001\001\056"
          + "\u4002\004\044\104\117\164\274\275\276\277\001\002\003\u8001\u8001\u8001\u8000\002\157\164\047\300"
          + "\001\u8002\001\056\u4002\003\143\152\162\301\302\303\001\002\u8000\u8000\u8000\002\152\170\304\305"
          + "\001\u8002\u8002\u8002\001\056\u4002\002\143\163\307\310\001\u8004\u8000\001\056\u4002\001\143\312"
          + "\002\150\151\313\314\001\u8001\u8001\004\143\144\155\156\315\316\000\320\001\002\020\u8002\u8002\002"
          + "\163\170\000\047\007\001\056\u4002\001\154\317\u8000\u8002\u8002\u8002\u8002\002\142\162\322\323\001"
          + "\u8002\u8002\u8002\002\143\164\325\326\001\u8002\u8002\u8002\002\151\164\047\330\001\u8002\001\056"
          + "\u4002\001\143\153\u8000\u8002\001\056\u4002\003\143\162\163\333\340\341\023\024\002\162\163\334\234"
          + "\001\u8000\003\101\107\130\335\336\337\001\002\u8000\u8000\u8000\u8000\u8000\u8002\u8002\002\143\165"
          + "\343\101\001\u8002\001\056\u4001\002\156\162\344\151\016\002\150\160\345\346\001\u8000\001\056\u4000"
          + "\001\152\347\u8001\002\163\164\350\351\001\u8000\u8000";
  private static final String[] TRIE_SEGMENTS = {
    "kka.",
    "ctor",
    "ell",
    "ystem",
    "mpl$",
    "oordinatedShutdown$",
    "ightArrayRevolverScheduler$",
    "cheduler$",
    "ttp.",
    "mpl",
    "ngine.",
    "lient.",
    "oolMasterActor",
    "ool.NewHostConnectionPool$",
    "ttp2.Http2Ext",
    "erver.HttpServerBluePrint$TimeoutAccessImpl$",
    "til.StreamUtils$",
    "caladsl",
    "ttp",
    "Ext",
    "xt",
    "tream",
    "mpl.",
    "an",
    "n$SubInput",
    "ut$SubstreamSubscription",
    "using.ActorGraphInterpreter$",
    "tage.",
    "raphStageLogic$",
    "imerGraphStageLogic$",
    ".qos.logback",
    "assic.",
    "ogger",
    "pi.LoggingEvent",
    "re.AsyncAppenderBase$Worker",
    "nnamon.",
    "ojure.",
    "elastic.apm.",
    ".",
    "ppdynamics.",
    "eust.jcommander.",
    "rrotsearch.hppc",
    "ashOrderMixing$",
    "ahale.metrics",
    "ervlets.",
    "trastsecurity.",
    "chbase.client.deps",
    "om.lmax.disruptor.",
    "o.netty.",
    "rg.LatencyUtils.",
    "ynatrace.",
    "asterxml.",
    "lassmate.",
    "ackson",
    "odule.afterburner.util.MyClassLoader",
    "thub.mustachejava.",
    "ogle.",
    "pi",
    "lient.http.HttpRequest",
    "oud.",
    "mmon",
    "ase.internal.Finalizer",
    "til.concurrent.",
    "son.",
    "n",
    "ect",
    "nternal.",
    "bstractBindingProcessor$",
    "ytecodeGen$",
    "glib.core.internal.$LoadingCache$",
    "trumentation.",
    "2objc.",
    "o",
    "ging.",
    "grunning.",
    "rotobuf.",
    "pc.",
    "irdparty.",
    "pe.",
    "ntellij.rt.debugger.",
    "yway.jsonpath.",
    "nspired.",
    "oadtrace.",
    "ightbend.lagom.",
    "change.v2.c3p0.",
    "ewrelic.",
    "6spy.",
    "ngularity.",
    "ersey.api.client",
    "essaging.",
    "atadog.",
    "pentracing.",
    "lf4j.",
    "race",
    "ootstrap.instrumentation.java.concurrent.RunnableWrapper",
    "ore.",
    "o.micro",
    "eter.",
    "aut.tracing.",
    "va",
    "ang.Throwable",
    "et.",
    "ttpURLConnection",
    "RL",
    "mi.",
    "til.",
    "oncurrent.",
    "ogging",
    "ogManager$Cleaner",
    "l.",
    "ml.",
    "k.",
    "otlin.",
    "ytebuddy.",
    "f.cglib.",
    "rg.",
    "ache.",
    "cel.",
    "elix.framework.URLHandlers",
    "roovy.",
    "tml.",
    "g4j",
    "ategory",
    "DC",
    "cene.",
    "egexp.",
    "artarus.",
    "lan.",
    "rces.",
    "ath.",
    "pectj.",
    "odehaus.groovy",
    "untime.",
    "clipse.osgi.",
    "ramework.internal.protocol.",
    "nternal.url.",
    "2",
    "river",
    "atabaseCloser",
    "nExitDatabaseCloser",
    "dbc",
    "tore.",
    "ileLock",
    "riterThread",
    "ools.Server",
    "athUtils$1",
    "ask",
    "on.simple.",
    "pringframework",
    "qp.",
    "p",
    "nterceptor.AsyncExecutionInterceptor",
    "ans",
    "actory.",
    "roovy.GroovyBeanDefinitionReader$",
    "upport.",
    "bstract",
    "utowireCapableBeanFactory",
    "eanFactory",
    "faultListableBeanFactory",
    "sposableBeanAdapter",
    "ot",
    "utoconfigure.",
    "ackgroundPreinitializer$",
    "ondition.OnClassCondition$",
    "ontext.embedded.",
    "nnotationConfigEmbeddedWebApplicationContext",
    "mbeddedWebApplicationContext",
    "omcat.TomcatEmbedded",
    "ervletContainer$",
    "ebappClassLoader",
    "eb.",
    "mbedded.",
    "etty.NettyWebServer$",
    "omcat.Tomcat",
    "mbeddedWebappClassLoader",
    "ebServer$1",
    "ervlet.context.",
    "nnotationConfigServletWebServerApplicationContext",
    "ervletWebServerApplicationContext",
    "che.",
    "lib",
    "ore.internal.LoadingCache",
    "text",
    "bstractApplicationContext$",
    "ontextTypeMatchClassLoader",
    "e",
    "Proxy",
    "ecoratingClassLoader",
    "verridingClassLoader",
    "ask.",
    "a",
    "onvert.ClassGeneratingEntityInstantiator$",
    "pa.repository.config.InspectionClassLoader",
    "epository.core.support.RepositoryFactorySupport",
    "b.",
    "pression.",
    "ormat.",
    "onverter.",
    "erver.reactive.",
    "nstrument",
    "lassloading.S",
    "adowingClassLoader",
    "mpleThrowawayClassLoader",
    "a.",
    "bc.",
    "istener.",
    "di.",
    "ang.",
    "jenesis.",
    "m.",
    "emoting.",
    "ripting.",
    "ereotype.",
    "ransaction.",
    "il",
    "alidation.",
    "eb",
    "ontext.",
    "equest.async.",
    "bstractRefreshableWebApplicationContext",
    "enericWebApplicationContext",
    "mlWebApplicationContext",
    "eactive.",
    "ervlet.",
    "aml.snakeyaml.",
    "ala.collection.",
    "et.www.",
    "ttp.HttpClient",
    "rotocol",
    "rt",
    "erver",
    "ransport",
  };

  public static final ToIntFunction<String> INSTANCE = new IgnoresTrie();

  private IgnoresTrie() {
    super(TRIE_DATA.toCharArray(), TRIE_SEGMENTS);
  }
}
