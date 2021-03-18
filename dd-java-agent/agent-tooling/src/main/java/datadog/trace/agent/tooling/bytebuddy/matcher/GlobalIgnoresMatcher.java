package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
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

  private final Set<String> springClassLoaderIgnores =
      new HashSet<>(
          Arrays.asList(
              "org.springframework.context.support.ContextTypeMatchClassLoader",
              "org.springframework.core.OverridingClassLoader",
              "org.springframework.core.DecoratingClassLoader",
              "org.springframework.instrument.classloading.SimpleThrowawayClassLoader",
              "org.springframework.instrument.classloading.ShadowingClassLoader"));

  /**
   * Be very careful about the types of matchers used in this section as they are called on every
   * class load, so they must be fast. Generally speaking try to only use name matchers as they
   * don't have to load additional info.
   */
  @Override
  public boolean matches(final TypeDescription target) {
    final String name = target.getActualName();
    switch (name.charAt(0) - 'a') {
        // starting at zero to get a tableswitch from javac, though it looks horrendous
      case 'a' - 'a':
        break;
      case 'b' - 'a':
        break;
      case 'c' - 'a':
        if (name.startsWith("ch.qos.logback.")) {
          // We instrument this Runnable
          if (name.equals("ch.qos.logback.core.AsyncAppenderBase$Worker")) {
            return false;
          }
          if (name.startsWith("ch.qos.logback.classic.spi.LoggingEvent")) {
            return false;
          }
          if (name.equals("ch.qos.logback.classic.Logger")) {
            return false;
          }
          return true;
        }
        if (name.startsWith("com.")) {
          if (name.startsWith("com.carrotsearch.hppc.")) {
            if (name.startsWith("com.carrotsearch.hppc.HashOrderMixing$")) {
              return false;
            }
            return true;
          }
          if (name.startsWith("com.codahale.metrics.")) {
            // We instrument servlets
            if (name.startsWith("com.codahale.metrics.servlets.")) {
              return false;
            }
            return true;
          }
          if (name.startsWith("com.couchbase.client.deps.")) {
            // Couchbase library includes some packaged dependencies, unfortunately some of them are
            // instrumented by java-concurrent instrumentation
            if (name.startsWith("com.couchbase.client.deps.io.netty.")
                || name.startsWith("com.couchbase.client.deps.org.LatencyUtils.")
                || name.startsWith("com.couchbase.client.deps.com.lmax.disruptor.")) {
              return false;
            }
            return true;
          }
          if (name.startsWith("com.fasterxml.jackson.")) {
            if (name.equals("com.fasterxml.jackson.module.afterburner.util.MyClassLoader")) {
              return false;
            }
            return true;
          }
          if (name.startsWith("com.google.")) {
            if (name.startsWith("com.google.cloud.")
                || name.startsWith("com.google.instrumentation.")
                || name.startsWith("com.google.j2objc.")
                || name.startsWith("com.google.gson.")
                || name.startsWith("com.google.logging.")
                || name.startsWith("com.google.longrunning.")
                || name.startsWith("com.google.protobuf.")
                || name.startsWith("com.google.rpc.")
                || name.startsWith("com.google.thirdparty.")
                || name.startsWith("com.google.type.")) {
              return true;
            }
            if (name.startsWith("com.google.common.")) {
              if (name.startsWith("com.google.common.util.concurrent.")
                  || name.equals("com.google.common.base.internal.Finalizer")) {
                return false;
              }
              return true;
            }
            if (name.startsWith("com.google.inject.")) {
              // We instrument Runnable there
              if (name.startsWith("com.google.inject.internal.AbstractBindingProcessor$")
                  || name.startsWith("com.google.inject.internal.BytecodeGen$")
                  || name.startsWith(
                      "com.google.inject.internal.cglib.core.internal.$LoadingCache$")) {
                return false;
              }
              return true;
            }
            if (name.startsWith("com.google.api.")) {
              if (name.startsWith("com.google.api.client.http.HttpRequest")) {
                return false;
              }
              return true;
            }
          }
          if (name.startsWith("com.sun.")) {
            return !name.startsWith("com.sun.messaging.")
                && !name.startsWith("com.sun.jersey.api.client");
          }
          if (name.startsWith("com.mchange.v2.c3p0.") && name.endsWith("Proxy")) {
            return true;
          }
          if (name.startsWith("com.appdynamics.")
              || name.startsWith("com.beust.jcommander.")
              || name.startsWith("com.dynatrace.")
              || name.startsWith("com.fasterxml.classmate.")
              || name.startsWith("com.github.mustachejava.")
              || name.startsWith("com.intellij.rt.debugger.")
              || name.startsWith("com.jayway.jsonpath.")
              || name.startsWith("com.jinspired.")
              || name.startsWith("com.jloadtrace.")
              || name.startsWith("com.lightbend.lagom.")
              || name.startsWith("com.p6spy.")
              || name.startsWith("com.newrelic.")
              || name.startsWith("com.singularity.")) {
            return true;
          }
        }
        if (name.startsWith("clojure.")) {
          return true;
        }
        if (name.startsWith("cinnamon.")) {
          return true;
        }
        break;
      case 'd' - 'a':
        if (name.startsWith("datadog.")) {
          if (name.startsWith("datadog.opentracing.")
              || name.startsWith("datadog.trace.core.")
              || name.startsWith("datadog.slf4j.")) {
            return true;
          }
          if (name.startsWith("datadog.trace.")) {
            // FIXME: We should remove this once
            // https://github.com/raphw/byte-buddy/issues/558 is fixed
            return !name.equals(
                "datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper");
          }
        }
        break;
      case 'e' - 'a':
        break;
      case 'f' - 'a':
        break;
      case 'g' - 'a':
        break;
      case 'h' - 'a':
        break;
      case 'i' - 'a':
        if (name.startsWith("io.micronaut.tracing.") || name.startsWith("io.micrometer.")) {
          return true;
        }
        break;
      case 'j' - 'a':
        if (name.startsWith("jdk.")) {
          return true;
        }
        if (name.startsWith("java.")) {
          // allow exception profiling instrumentation
          if (name.equals("java.lang.Throwable")) {
            return false;
          }
          if (name.equals("java.net.URL") || name.equals("java.net.HttpURLConnection")) {
            return false;
          }
          if (name.startsWith("java.rmi.") || name.startsWith("java.util.concurrent.")) {
            return false;
          }
          // Concurrent instrumentation modifies the structure of
          // Cleaner class incompatibly with java9+ modules.
          // Working around until a long-term fix for modules can be
          // put in place.
          return !name.startsWith("java.util.logging.")
              || name.equals("java.util.logging.LogManager$Cleaner");
        }
        if (name.startsWith("javax.el.") || name.startsWith("javax.xml.")) {
          return true;
        }
        break;
      case 'k' - 'a':
        // kotlin, note we do not ignore kotlinx because we instrument coroutins code
        if (name.startsWith("kotlin.")) {
          return true;
        }
        break;
      case 'l' - 'a':
        break;
      case 'm' - 'a':
        break;
      case 'n' - 'a':
        if (name.startsWith("net.bytebuddy.") || name.startsWith("net.sf.cglib.")) {
          return true;
        }
        break;
      case 'o' - 'a':
        if (name.startsWith("org.")) {
          if (name.startsWith("org.apache.")) {
            if (name.startsWith("org.apache.log4j.")) {
              return !name.equals("org.apache.log4j.MDC")
                  && !name.equals("org.apache.log4j.spi.LoggingEvent")
                  && !name.equals("org.apache.log4j.Category");
            }
            if (name.startsWith("org.apache.bcel.")
                || name.startsWith("org.apache.groovy.")
                || name.startsWith("org.apache.html.")
                || name.startsWith("org.apache.lucene.")
                || name.startsWith("org.apache.regexp.")
                || name.startsWith("org.apache.tartarus.")
                || name.startsWith("org.apache.wml.")
                || name.startsWith("org.apache.xalan.")
                || name.startsWith("org.apache.xerces.")
                || name.startsWith("org.apache.xml.")
                || name.startsWith("org.apache.xpath.")) {
              return true;
            }
          }
          if (name.startsWith("org.codehaus.groovy.")) {
            // We seem to instrument some classes in runtime
            return !name.startsWith("org.codehaus.groovy.runtime.");
          }
          if (name.startsWith("org.h2.")) {
            if (name.equals("org.h2.Driver")
                || name.startsWith("org.h2.jdbc.")
                || name.startsWith("org.h2.jdbcx.")
                // Some runnables that get instrumented
                || name.equals("org.h2.util.Task")
                || name.equals("org.h2.util.MathUtils$1")
                || name.equals("org.h2.store.FileLock")
                || name.equals("org.h2.engine.DatabaseCloser")
                || name.equals("org.h2.engine.OnExitDatabaseCloser")) {
              return false;
            }
            return true;
          }
          if (name.startsWith("org.springframework.")) {
            if (springClassLoaderIgnores.contains(name)) {
              return true;
            }
            if ((name.startsWith("org.springframework.aop.")
                    && !name.equals(
                        "org.springframework.aop.interceptor.AsyncExecutionInterceptor"))
                || name.startsWith("org.springframework.cache.")
                || name.startsWith("org.springframework.dao.")
                || name.startsWith("org.springframework.ejb.")
                || name.startsWith("org.springframework.expression.")
                || name.startsWith("org.springframework.format.")
                || name.startsWith("org.springframework.jca.")
                || name.startsWith("org.springframework.jdbc.")
                || name.startsWith("org.springframework.jmx.")
                || name.startsWith("org.springframework.jndi.")
                || name.startsWith("org.springframework.lang.")
                || name.startsWith("org.springframework.messaging.")
                || name.startsWith("org.springframework.objenesis.")
                || name.startsWith("org.springframework.orm.")
                || name.startsWith("org.springframework.remoting.")
                || name.startsWith("org.springframework.scripting.")
                || name.startsWith("org.springframework.stereotype.")
                || name.startsWith("org.springframework.transaction.")
                || name.startsWith("org.springframework.ui.")
                || name.startsWith("org.springframework.validation.")) {
              return true;
            }

            if (name.startsWith("org.springframework.data.")) {
              if (name.equals(
                      "org.springframework.data.repository.core.support.RepositoryFactorySupport")
                  || name.startsWith(
                      "org.springframework.data.convert.ClassGeneratingEntityInstantiator$")
                  || name.equals(
                      "org.springframework.data.jpa.repository.config.InspectionClassLoader")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.amqp.")) {
              return false;
            }

            if (name.startsWith("org.springframework.beans.")) {
              if (name.equals("org.springframework.beans.factory.support.DisposableBeanAdapter")
                  || name.startsWith(
                      "org.springframework.beans.factory.groovy.GroovyBeanDefinitionReader$")
                  || name.equals("org.springframework.beans.factory.support.AbstractBeanFactory")
                  || name.equals(
                      "org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory")
                  || name.equals(
                      "org.springframework.beans.factory.support.DefaultListableBeanFactory")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.boot.")) {
              // More runnables to deal with
              if (name.startsWith(
                      "org.springframework.boot.autoconfigure.BackgroundPreinitializer$")
                  || name.startsWith(
                      "org.springframework.boot.autoconfigure.condition.OnClassCondition$")
                  || name.startsWith("org.springframework.boot.web.embedded.netty.NettyWebServer$")
                  || name.startsWith(
                      "org.springframework.boot.web.embedded.tomcat.TomcatWebServer$1")
                  || name.startsWith(
                      "org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainer$")
                  || name.equals(
                      "org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedWebappClassLoader")
                  || name.equals(
                      "org.springframework.boot.web.embedded.tomcat.TomcatEmbeddedWebappClassLoader")
                  || name.equals(
                      "org.springframework.boot.context.embedded.EmbeddedWebApplicationContext")
                  || name.equals(
                      "org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext")
                  || name.equals(
                      "org.springframework.boot.context.embedded.AnnotationConfigEmbeddedWebApplicationContext")
                  || name.equals(
                      "org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.cglib.")) {
              // This class contains nested Callable instance that we'd happily not touch, but
              // unfortunately our field injection code is not flexible enough to realize that, so
              // instead
              // we instrument this Callable to make tests happy.
              if (name.startsWith("org.springframework.cglib.core.internal.LoadingCache$")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.context.")) {
              // More runnables to deal with
              if (name.startsWith(
                  "org.springframework.context.support.AbstractApplicationContext$")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.core.")) {
              if (name.startsWith("org.springframework.core.task.")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.instrument.")) {
              return true;
            }

            if (name.startsWith("org.springframework.http.")) {
              // There are some Mono implementation that get instrumented
              if (name.startsWith("org.springframework.http.server.reactive.")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.jms.")) {
              if (name.startsWith("org.springframework.jms.listener.")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.util.")) {
              if (name.startsWith("org.springframework.util.concurrent.")) {
                return false;
              }
              return true;
            }

            if (name.startsWith("org.springframework.web.")) {
              if (name.startsWith("org.springframework.web.servlet.")
                  || name.startsWith("org.springframework.web.reactive.")
                  || name.startsWith("org.springframework.web.context.request.async.")
                  || name.equals(
                      "org.springframework.web.context.support.AbstractRefreshableWebApplicationContext")
                  || name.equals(
                      "org.springframework.web.context.support.GenericWebApplicationContext")
                  || name.equals(
                      "org.springframework.web.context.support.XmlWebApplicationContext")) {
                return false;
              }
              return true;
            }
            return false;
          }
          if (name.startsWith("org.aspectj.")
              || name.startsWith("org.groovy.")
              || name.startsWith("org.jinspired.")
              || name.startsWith("org.json.simple.")
              || name.startsWith("org.xml.")
              || name.startsWith("org.yaml.snakeyaml.")) {
            return true;
          }
        }
        break;
      case 'p' - 'a':
        break;
      case 'q' - 'a':
        break;
      case 'r' - 'a':
        break;
      case 's' - 'a':
        if (name.startsWith("sun.")) {
          return !name.startsWith("sun.net.www.protocol.")
              && !name.startsWith("sun.rmi.server")
              && !name.startsWith("sun.rmi.transport")
              && !name.equals("sun.net.www.http.HttpClient");
        }
        break;
      default:
    }

    final int firstDollar = name.indexOf('$');
    if (firstDollar > -1) {
      // clojure class patterns
      if (name.startsWith("loader__", firstDollar + 1)) {
        return true;
      }
      int dollar = firstDollar;
      while (dollar > -1) {
        if (name.startsWith("fn__", dollar + 1) || name.startsWith("reify__", dollar + 1)) {
          return true;
        }
        dollar = name.indexOf('$', dollar + 1);
      }

      if (name.contains("$JaxbAccessor")
          || name.contains("CGLIB$$")
          || name.contains("$__sisu")
          || name.contains("$$EnhancerByProxool$$")
          || name.startsWith("org.springframework.core.$Proxy")) {
        return true;
      }
    }
    if (name.contains("javassist") || name.contains(".asm.")) {
      return true;
    }

    return false;
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
