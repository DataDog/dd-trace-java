package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.regex.Pattern;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Global ignores matcher used by the agent.
 *
 * <p>This matcher services two main purposes:
 * <li>
 *
 *     <ul>
 *       Ignore classes that are unsafe or pointless to transform. 'System' level classes like jvm
 *       classes or groovy classes, other tracers, debuggers, etc.
 * </ul>
 *
 * <ul>
 *   Ignore additional classes to minimize number of classes we apply expensive matchers to.
 * </ul>
 */
public final class GlobalIgnoresMatcher
    extends ElementMatcher.Junction.AbstractBase<TypeDescription> {
  private static final GlobalIgnoresMatcher INSTANCE = new GlobalIgnoresMatcher();

  public static GlobalIgnoresMatcher globalIgnoresMatcher() {
    return GlobalIgnoresMatcher.INSTANCE;
  }

  private static final Pattern IGNORE =
      Pattern.compile(
          "(^(ch\\.qos\\.logback\\.|com\\.carrotsearch\\.hppc\\.|com\\.couchbase\\.client\\.deps\\.|cinnamon\\.|datadog\\.io\\.micronaut\\.tracing\\.|io\\.micrometer\\.|jdk\\.|java\\.|javax\\.el\\.|javax\\.xml\\.|kotlin\\.|net\\.bytebuddy\\.|net\\.sf\\.cglib\\.|org\\.apache\\.log4j\\.|org\\.apache\\.bcel\\.|org\\.apache\\.groovy\\.|org\\.apache\\.html\\.|org\\.apache\\.lucene\\.|org\\.apache\\.regexp\\.|org\\.apache\\.tartarus\\.|org\\.apache\\.wml\\.|org\\.apache\\.xalan\\.|org\\.apache\\.xerces\\.|org\\.apache\\.xml\\.|org\\.apache\\.xpath\\.|org\\.codehaus\\.groovy\\.|org\\.h2\\.|org\\.springframework\\.aop\\.|org\\.springframework\\.cache\\.|org\\.springframework\\.dao\\.|org\\.springframework\\.ejb\\.|org\\.springframework\\.expression\\.|org\\.springframework\\.format\\.|org\\.springframework\\.jca\\.|org\\.springframework\\.jdbc\\.|org\\.springframework\\.jmx\\.|org\\.springframework\\.jndi\\.|org\\.springframework\\.lang\\.|org\\.springframework\\.messaging\\.|org\\.springframework\\.objenesis\\.|org\\.springframework\\.orm\\.|org\\.springframework\\.remoting\\.|org\\.springframework\\.scripting\\.|org\\.springframework\\.stereotype\\.|org\\.springframework\\.transaction\\.|org\\.springframework\\.ui\\.|org\\.springframework\\.validation\\.|org\\.springframework\\.data\\.|org\\.springframework\\.beans\\.|org\\.springframework\\.boot\\.|org\\.springframework\\.cglib\\.|org\\.springframework\\.context\\.|org\\.springframework\\.core\\.|org\\.springframework\\.instrument\\.|org\\.springframework\\.http\\.|org\\.springframework\\.jms\\.|org\\.springframework\\.util\\.|org\\.springframework\\.web\\.|org\\.aspectj\\.|org\\.groovy\\.|org\\.jinspired\\.|org\\.json\\.simple\\.|org\\.xml\\.|org\\.yaml\\.snakeyaml\\.|sun\\.|org\\.springframework\\.core\\.\\$Proxy|com\\.sun\\.proxy\\.).*)|^(java\\.util\\.logging\\.LogManager\\$Cleaner|org\\.springframework\\.context\\.support\\.ContextTypeMatchClassLoader|org\\.springframework\\.core\\.OverridingClassLoader|org\\.springframework\\.core\\.DecoratingClassLoader|org\\.springframework\\.instrument\\.classloading\\.SimpleThrowawayClassLoader|org\\.springframework\\.instrument\\.classloading\\.ShadowingClassLoader)$|(.*(\\$loader__|\\$fn__|\\$reify__|\\$JaxbAccessor|\\$__sisu|CGLIB\\$\\$|\\$\\$EnhancerByProxool\\$\\$).*)");

  private static final Pattern KEEP =
      Pattern.compile(
          "(^(java\\.rmi\\.|java\\.util\\.concurrent\\.|java\\.util\\.logging\\.|org\\.codehaus\\.groovy\\.runtime\\.|org\\.h2\\.jdbc\\.|org\\.h2\\.jdbcx\\.|org\\.springframework\\.amqp\\.|org\\.springframework\\.beans\\.factory\\.groovy\\.GroovyBeanDefinitionReader\\$|org\\.springframework\\.boot\\.autoconfigure\\.BackgroundPreinitializer\\$|org\\.springframework\\.boot\\.autoconfigure\\.condition\\.OnClassCondition\\$|org\\.springframework\\.boot\\.web\\.embedded\\.netty\\.NettyWebServer\\$|org\\.springframework\\.boot\\.web\\.embedded\\.tomcat\\.TomcatWebServer\\$1|org\\.springframework\\.boot\\.context\\.embedded\\.tomcat\\.TomcatEmbeddedServletContainer\\$|org\\.springframework\\.cglib\\.core\\.internal\\.LoadingCache\\$|org\\.springframework\\.context\\.support\\.AbstractApplicationContext\\$|org\\.springframework\\.core\\.task\\.|org\\.springframework\\.jms\\.listener\\.|org\\.springframework\\.http\\.server\\.reactive\\.|org\\.springframework\\.util\\.concurrent\\.|org\\.springframework\\.web\\.servlet\\.|org\\.springframework\\.web\\.reactive\\.|org\\.springframework\\.web\\.context\\.request\\.async\\.|sun\\.net\\.www\\.protocol\\.|sun\\.rmi\\.server|sun\\.rmi\\.transport).*)|^(java\\.lang\\.Throwable|java\\.net\\.URL|java\\.net\\.HttpURLConnection|org\\.h2\\.Driver|org\\.h2\\.util\\.Task|org\\.h2\\.util\\.MathUtils\\$1|org\\.h2\\.store\\.FileLock|org\\.h2\\.engine\\.DatabaseCloser|org\\.h2\\.engine\\.OnExitDatabaseCloser|org\\.springframework\\.beans\\.factory\\.support\\.DisposableBeanAdapter|org\\.springframework\\.beans\\.factory\\.support\\.AbstractBeanFactory|org\\.springframework\\.beans\\.factory\\.support\\.AbstractAutowireCapableBeanFactory|org\\.springframework\\.beans\\.factory\\.support\\.DefaultListableBeanFactory|org\\.springframework\\.boot\\.context\\.embedded\\.tomcat\\.TomcatEmbeddedWebappClassLoader|org\\.springframework\\.boot\\.web\\.embedded\\.tomcat\\.TomcatEmbeddedWebappClassLoader|org\\.springframework\\.boot\\.web\\.servlet\\.context\\.ServletWebServerApplicationContext|org\\.springframework\\.boot\\.context\\.embedded\\.AnnotationConfigEmbeddedWebApplicationContext|org\\.springframework\\.boot\\.web\\.servlet\\.context\\.AnnotationConfigServletWebServerApplicationContext|org\\.springframework\\.web\\.context\\.support\\.AbstractRefreshableWebApplicationContext|org\\.springframework\\.web\\.context\\.support\\.GenericWebApplicationContext|org\\.springframework\\.web\\.context\\.support\\.XmlWebApplicationContext|sun\\.net\\.www\\.http\\.HttpClient)$");

  /**
   * Be very careful about the types of matchers used in this section as they are called on every
   * class load, so they must be fast. Generally speaking try to only use name matchers as they
   * don't have to load additional info.
   */
  @Override
  public boolean matches(final TypeDescription target) {
    String name = target.getActualName();
    return IGNORE.matcher(name).matches() && !KEEP.matcher(name).matches();
  }

  @Override
  public String toString() {
    return "globalIgnoresMatcher()";
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    } else if (null == other) {
      return false;
    } else {
      return getClass() == other.getClass();
    }
  }

  @Override
  public int hashCode() {
    return 17;
  }
}
