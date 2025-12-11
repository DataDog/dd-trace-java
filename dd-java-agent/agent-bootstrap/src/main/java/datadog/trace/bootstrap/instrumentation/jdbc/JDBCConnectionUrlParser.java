package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.DBInfo.DEFAULT;
import static java.lang.Math.max;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.ExceptionLogger;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured as an enum instead of a class hierarchy to allow iterating through the parsers
 * automatically without having to maintain a separate list of parsers. This is put in the bootstrap
 * project to keep the Muzzle generated dependency references smaller.
 */
public enum JDBCConnectionUrlParser {
  GENERIC_URL_LIKE() {
    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      try {
        // Attempt generic parsing
        final URI uri = new URI(jdbcUrl);

        populateStandardProperties(builder, splitQuery(uri.getQuery(), '&'));

        final String user = uri.getUserInfo();
        if (user != null) {
          builder.user(user);
        }

        String path = uri.getPath();

        if (path.startsWith("/")) {
          path = path.substring(1);
        }
        if (!path.isEmpty()) {
          builder.db(path);
        }

        if (uri.getHost() != null) {
          builder.host(uri.getHost());
        }

        if (uri.getPort() > 0) {
          builder.port(uri.getPort());
        }

        return builder.type(uri.getScheme());
      } catch (final Exception e) {
        return builder;
      }
    }
  },

  MODIFIED_URL_LIKE() {
    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      String serverName = "";
      Integer port = null;
      String instanceName = null;
      final int hostIndex = jdbcUrl.indexOf("://");

      if (hostIndex <= 0) {
        return builder;
      }

      final String type = jdbcUrl.substring(0, hostIndex);
      final String urlPart1;
      final String urlPart2;
      final int paramLoc;

      if (type.equals("db2") || type.equals("as400")) {
        if (jdbcUrl.contains("=")) {
          paramLoc = jdbcUrl.lastIndexOf(':');
          urlPart1 = jdbcUrl.substring(0, paramLoc);
          urlPart2 = jdbcUrl.substring(paramLoc + 1);

        } else {
          urlPart1 = jdbcUrl;
          urlPart2 = null;
        }
      } else {
        paramLoc = jdbcUrl.indexOf(';');
        urlPart1 = paramLoc >= 0 ? jdbcUrl.substring(0, paramLoc) : jdbcUrl;
        urlPart2 = paramLoc >= 0 ? jdbcUrl.substring(paramLoc + 1) : null;
      }

      if (urlPart2 != null) {
        final Map<String, String> props = splitQuery(urlPart2, ';');
        populateStandardProperties(builder, props);
        if (props.containsKey("servername")) {
          serverName = props.get("servername");
        }
      }

      final String urlServerName = urlPart1.substring(hostIndex + 3);
      if (!urlServerName.isEmpty()) {
        serverName = urlServerName;
      }

      int instanceLoc = serverName.indexOf('/');
      if (instanceLoc > 1) {
        instanceName = serverName.substring(instanceLoc + 1);
        serverName = serverName.substring(0, instanceLoc);
      }

      final int portLoc = serverName.indexOf(':');

      if (portLoc > 1) {
        port = Integer.parseInt(serverName.substring(portLoc + 1));
        serverName = serverName.substring(0, portLoc);
      }

      instanceLoc = serverName.indexOf('\\');
      if (instanceLoc > 1) {
        instanceName = serverName.substring(instanceLoc + 1);
        serverName = serverName.substring(0, instanceLoc);
      }

      if (instanceName != null) {
        builder.instance(instanceName);
      }

      if (!serverName.isEmpty()) {
        builder.host(serverName);
      }

      if (port != null) {
        builder.port(port);
      }

      return builder.type(type);
    }
  },

  POSTGRES("postgresql", "edb") {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5432;

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      final DBInfo dbInfo = builder.build();
      if (dbInfo.getHost() == null) {
        builder.host(DEFAULT_HOST);
      }
      if (dbInfo.getPort() == null) {
        builder.port(DEFAULT_PORT);
      }
      return GENERIC_URL_LIKE.doParse(jdbcUrl, builder);
    }
  },

  MYSQL("mysql", "mariadb") {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 3306;

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      final DBInfo dbInfo = builder.build();
      if (dbInfo.getHost() == null) {
        builder.host(DEFAULT_HOST);
      }
      if (dbInfo.getPort() == null) {
        builder.port(DEFAULT_PORT);
      }
      final int protoLoc = jdbcUrl.indexOf("://");
      final int typeEndLoc = dbInfo.getType().length();
      if (protoLoc > typeEndLoc && !jdbcUrl.substring(typeEndLoc + 1, protoLoc).equals("aws")) {
        return MARIA_SUBPROTO
            .doParse(jdbcUrl.substring(protoLoc + 3), builder)
            .subtype(jdbcUrl.substring(typeEndLoc + 1, protoLoc));
      }
      if (protoLoc > 0) {
        return GENERIC_URL_LIKE.doParse(dbInfo.getType() + jdbcUrl.substring(protoLoc), builder);
      }

      final int hostEndLoc;
      final int portLoc = jdbcUrl.indexOf(':', typeEndLoc + 1);
      final int dbLoc = jdbcUrl.indexOf('/', typeEndLoc);
      final int paramLoc = jdbcUrl.indexOf('?', dbLoc);

      if (paramLoc > 0) {
        populateStandardProperties(builder, splitQuery(jdbcUrl.substring(paramLoc + 1), '&'));
        builder.db(jdbcUrl.substring(dbLoc + 1, paramLoc));
      } else {
        builder.db(jdbcUrl.substring(dbLoc + 1));
      }

      if (portLoc > 0) {
        hostEndLoc = portLoc;
        try {
          builder.port(Integer.parseInt(jdbcUrl.substring(portLoc + 1, dbLoc)));
        } catch (final NumberFormatException ignored) {
        }
      } else {
        hostEndLoc = dbLoc;
      }

      builder.host(jdbcUrl.substring(typeEndLoc + 1, hostEndLoc));

      return builder;
    }
  },

  MARIA_SUBPROTO() {
    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      if (jdbcUrl.startsWith("**internally_generated**")) {
        // there is nothing to parse
        builder.host(null);
        builder.port(null);
        return builder;
      }
      final int hostEndLoc;
      final int clusterSepLoc = jdbcUrl.indexOf(',');
      final int ipv6End =
          !jdbcUrl.isEmpty() && jdbcUrl.charAt(0) == '[' ? jdbcUrl.indexOf(']') : -1;
      int portLoc = jdbcUrl.indexOf(':', max(0, ipv6End));
      portLoc = -1 < clusterSepLoc && clusterSepLoc < portLoc ? -1 : portLoc;
      final int dbLoc = jdbcUrl.indexOf('/', max(portLoc, clusterSepLoc));

      final int paramLoc = jdbcUrl.indexOf('?', dbLoc);

      if (paramLoc > 0) {
        populateStandardProperties(builder, splitQuery(jdbcUrl.substring(paramLoc + 1), '&'));
        builder.db(jdbcUrl.substring(dbLoc + 1, paramLoc));
      } else {
        builder.db(jdbcUrl.substring(dbLoc + 1));
      }

      if (jdbcUrl.startsWith("address=")) {
        return MARIA_ADDRESS.doParse(jdbcUrl, builder);
      }

      if (portLoc > 0) {
        hostEndLoc = portLoc;
        final int portEndLoc = clusterSepLoc > 0 ? clusterSepLoc : dbLoc;
        try {
          builder.port(Integer.parseInt(jdbcUrl.substring(portLoc + 1, portEndLoc)));
        } catch (final NumberFormatException ignored) {
        }
      } else {
        hostEndLoc = clusterSepLoc > 0 ? clusterSepLoc : dbLoc;
      }

      if (ipv6End > 0) {
        builder.host(jdbcUrl.substring(1, ipv6End));
      } else {
        builder.host(jdbcUrl.substring(0, hostEndLoc));
      }
      return builder;
    }
  },

  MARIA_ADDRESS() {
    private final Pattern HOST_REGEX = Pattern.compile("\\(\\s*host\\s*=\\s*([^ )]+)\\s*\\)");
    private final Pattern PORT_REGEX = Pattern.compile("\\(\\s*port\\s*=\\s*([\\d]+)\\s*\\)");
    private final Pattern USER_REGEX = Pattern.compile("\\(\\s*user\\s*=\\s*([^ )]+)\\s*\\)");

    @Override
    DBInfo.Builder doParse(String jdbcUrl, final DBInfo.Builder builder) {
      final int addressEnd = jdbcUrl.indexOf(",address=");
      if (addressEnd > 0) {
        jdbcUrl = jdbcUrl.substring(0, addressEnd);
      }
      final Matcher hostMatcher = HOST_REGEX.matcher(jdbcUrl);
      if (hostMatcher.find()) {
        builder.host(hostMatcher.group(1));
      }

      final Matcher portMatcher = PORT_REGEX.matcher(jdbcUrl);
      if (portMatcher.find()) {
        builder.port(Integer.parseInt(portMatcher.group(1)));
      }

      final Matcher userMatcher = USER_REGEX.matcher(jdbcUrl);
      if (userMatcher.find()) {
        builder.user(userMatcher.group(1));
      }

      return builder;
    }
  },

  SAP("sap") {
    private static final String DEFAULT_HOST = "localhost";

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      final DBInfo dbInfo = builder.build();
      if (dbInfo.getHost() == null) {
        builder.host(DEFAULT_HOST);
      }
      return GENERIC_URL_LIKE.doParse(jdbcUrl, builder);
    }
  },

  MSSQLSERVER("microsoft", "sqlserver") {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 1433;

    @Override
    DBInfo.Builder doParse(String jdbcUrl, final DBInfo.Builder builder) {
      if (jdbcUrl.startsWith("microsoft:")) {
        jdbcUrl = jdbcUrl.substring("microsoft:".length());
      }
      if (!jdbcUrl.startsWith("sqlserver://")) {
        return builder;
      }
      builder.type("sqlserver");
      final DBInfo dbInfo = builder.build();
      if (dbInfo.getHost() == null) {
        builder.host(DEFAULT_HOST);
      }
      if (dbInfo.getPort() == null) {
        builder.port(DEFAULT_PORT);
      }
      return MODIFIED_URL_LIKE.doParse(jdbcUrl, builder);
    }
  },

  DB2("db2", "as400") {
    private static final int DEFAULT_PORT = 50000;

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      final DBInfo dbInfo = builder.build();
      if (dbInfo.getPort() == null) {
        builder.port(DEFAULT_PORT);
      }
      return MODIFIED_URL_LIKE.doParse(jdbcUrl, builder);
    }
  },

  ORACLE("oracle") {
    private static final int DEFAULT_PORT = 1521;

    @Override
    DBInfo.Builder doParse(String jdbcUrl, final DBInfo.Builder builder) {
      final int typeEndIndex = jdbcUrl.indexOf(':', "oracle:".length());
      final String subtype = jdbcUrl.substring("oracle:".length(), typeEndIndex);
      jdbcUrl = jdbcUrl.substring(typeEndIndex + 1);

      builder.subtype(subtype);
      final DBInfo dbInfo = builder.build();
      if (dbInfo.getPort() == null) {
        builder.port(DEFAULT_PORT);
      }

      if (jdbcUrl.contains("@")) {
        return ORACLE_AT.doParse(jdbcUrl, builder);
      } else {
        return ORACLE_CONNECT_INFO.doParse(jdbcUrl, builder);
      }
    }
  },

  ORACLE_CONNECT_INFO() {
    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {

      final String host;
      final Integer port;
      final String instance;

      final int hostEnd = jdbcUrl.indexOf(':');
      final int instanceLoc = jdbcUrl.indexOf('/');
      if (hostEnd > 0) {
        host = jdbcUrl.substring(0, hostEnd);
        final int afterHostEnd = jdbcUrl.indexOf(':', hostEnd + 1);
        if (afterHostEnd > 0) {
          port = Integer.parseInt(jdbcUrl.substring(hostEnd + 1, afterHostEnd));
          instance = jdbcUrl.substring(afterHostEnd + 1);
        } else {
          if (instanceLoc > 0) {
            instance = jdbcUrl.substring(instanceLoc + 1);
            port = Integer.parseInt(jdbcUrl.substring(hostEnd + 1, instanceLoc));
          } else {
            final String portOrInstance = jdbcUrl.substring(hostEnd + 1);
            Integer parsedPort = null;
            try {
              parsedPort = Integer.parseInt(portOrInstance);
            } catch (final NumberFormatException ignored) {
            }
            if (parsedPort == null) {
              port = null;
              instance = portOrInstance;
            } else {
              port = parsedPort;
              instance = null;
            }
          }
        }
      } else {
        if (instanceLoc > 0) {
          host = jdbcUrl.substring(0, instanceLoc);
          port = null;
          instance = jdbcUrl.substring(instanceLoc + 1);
        } else {
          if (jdbcUrl.isEmpty()) {
            return builder;
          } else {
            host = null;
            port = null;
            instance = jdbcUrl;
          }
        }
      }
      if (host != null) {
        builder.host(host);
      }
      if (port != null) {
        builder.port(port);
      }
      return builder.instance(instance);
    }
  },

  ORACLE_AT() {
    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      if (jdbcUrl.contains("@(description")) {
        return ORACLE_AT_DESCRIPTION.doParse(jdbcUrl, builder);
      }
      final String user;

      int atIndex = jdbcUrl.indexOf('@');
      final String urlPart1 = jdbcUrl.substring(0, atIndex);
      final String connectInfo = jdbcUrl.substring(atIndex + 1);

      final int userInfoLoc = urlPart1.indexOf('/');
      if (userInfoLoc > 0) {
        user = urlPart1.substring(0, userInfoLoc);
      } else {
        user = null;
      }

      final int hostStart;
      if (connectInfo.startsWith("//")) {
        hostStart = "//".length();
      } else if (connectInfo.startsWith("ldap://")) {
        hostStart = "ldap://".length();
      } else {
        hostStart = 0;
      }
      if (user != null) {
        builder.user(user);
      }
      return ORACLE_CONNECT_INFO.doParse(connectInfo.substring(hostStart), builder);
    }
  },

  /**
   * This parser can locate incorrect data if multiple addresses are defined but not everything is
   * defined in the first block. (It would locate data from subsequent address blocks.
   */
  ORACLE_AT_DESCRIPTION() {
    private final Pattern HOST_REGEX = Pattern.compile("\\(\\s*host\\s*=\\s*([^ )]+)\\s*\\)");
    private final Pattern PORT_REGEX = Pattern.compile("\\(\\s*port\\s*=\\s*([\\d]+)\\s*\\)");
    private final Pattern INSTANCE_REGEX =
        Pattern.compile("\\(\\s*service_name\\s*=\\s*([^ )]+)\\s*\\)");

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      int atIndex = jdbcUrl.indexOf('@');
      final String urlPart1 = jdbcUrl.substring(0, atIndex);
      final String urlPart2 = jdbcUrl.substring(atIndex + 1);

      final int userInfoLoc = urlPart1.indexOf('/');
      if (userInfoLoc > 0) {
        builder.user(urlPart1.substring(0, userInfoLoc));
      }

      final Matcher hostMatcher = HOST_REGEX.matcher(urlPart2);
      if (hostMatcher.find()) {
        builder.host(hostMatcher.group(1));
      }

      final Matcher portMatcher = PORT_REGEX.matcher(urlPart2);
      if (portMatcher.find()) {
        builder.port(Integer.parseInt(portMatcher.group(1)));
      }

      final Matcher instanceMatcher = INSTANCE_REGEX.matcher(urlPart2);
      if (instanceMatcher.find()) {
        builder.instance(instanceMatcher.group(1));
      }

      return builder;
    }
  },

  H2("h2") {
    private static final int DEFAULT_PORT = 8082;

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      final String instance;

      final String h2Url = jdbcUrl.substring("h2:".length());
      if (h2Url.startsWith("mem:")) {
        builder.subtype("mem");
        final int propLoc = h2Url.indexOf(';');
        if (propLoc >= 0) {
          instance = h2Url.substring("mem:".length(), propLoc);
        } else {
          instance = h2Url.substring("mem:".length());
        }
      } else if (h2Url.startsWith("file:")) {
        builder.subtype("file");
        final int propLoc = h2Url.indexOf(';');
        if (propLoc >= 0) {
          instance = h2Url.substring("file:".length(), propLoc);
        } else {
          instance = h2Url.substring("file:".length());
        }
      } else if (h2Url.startsWith("zip:")) {
        builder.subtype("zip");
        final int propLoc = h2Url.indexOf(';');
        if (propLoc >= 0) {
          instance = h2Url.substring("zip:".length(), propLoc);
        } else {
          instance = h2Url.substring("zip:".length());
        }
      } else if (h2Url.startsWith("tcp:")) {
        final DBInfo dbInfo = builder.build();
        if (dbInfo.getPort() == null) {
          builder.port(DEFAULT_PORT);
        }
        return MODIFIED_URL_LIKE.doParse(jdbcUrl, builder).type("h2").subtype("tcp");
      } else if (h2Url.startsWith("ssl:")) {
        final DBInfo dbInfo = builder.build();
        if (dbInfo.getPort() == null) {
          builder.port(DEFAULT_PORT);
        }
        return MODIFIED_URL_LIKE.doParse(jdbcUrl, builder).type("h2").subtype("ssl");
      } else {
        builder.subtype("file");
        final int propLoc = h2Url.indexOf(';');
        if (propLoc >= 0) {
          instance = h2Url.substring(0, propLoc);
        } else {
          instance = h2Url;
        }
      }
      if (!instance.isEmpty()) {
        builder.instance(instance);
      }
      return builder;
    }
  },

  HSQL("hsqldb") {
    private static final String DEFAULT_USER = "SA";
    private static final int DEFAULT_PORT = 9001;

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      String instance;
      final DBInfo dbInfo = builder.build();
      if (dbInfo.getUser() == null) {
        builder.user(DEFAULT_USER);
      }
      final String hsqlUrl = jdbcUrl.substring("hsqldb:".length());
      if (hsqlUrl.startsWith("mem:")) {
        builder.subtype("mem");
        instance = hsqlUrl.substring("mem:".length());
      } else if (hsqlUrl.startsWith("file:")) {
        builder.subtype("file");
        instance = hsqlUrl.substring("file:".length());
      } else if (hsqlUrl.startsWith("res:")) {
        builder.subtype("res");
        instance = hsqlUrl.substring("res:".length());
      } else if (hsqlUrl.startsWith("hsql:")) {
        if (dbInfo.getPort() == null) {
          builder.port(DEFAULT_PORT);
        }
        return MODIFIED_URL_LIKE.doParse(jdbcUrl, builder).type("hsqldb").subtype("hsql");
      } else if (hsqlUrl.startsWith("hsqls:")) {
        if (dbInfo.getPort() == null) {
          builder.port(DEFAULT_PORT);
        }
        return MODIFIED_URL_LIKE.doParse(jdbcUrl, builder).type("hsqldb").subtype("hsqls");
      } else if (hsqlUrl.startsWith("http:")) {
        if (dbInfo.getPort() == null) {
          builder.port(80);
        }
        return MODIFIED_URL_LIKE.doParse(jdbcUrl, builder).type("hsqldb").subtype("http");
      } else if (hsqlUrl.startsWith("https:")) {
        if (dbInfo.getPort() == null) {
          builder.port(443);
        }
        return MODIFIED_URL_LIKE.doParse(jdbcUrl, builder).type("hsqldb").subtype("https");
      } else {
        builder.subtype("mem");
        instance = hsqlUrl;
      }
      return builder.instance(instance);
    }
  },

  DERBY("derby") {
    private static final String DEFAULT_USER = "APP";
    private static final int DEFAULT_PORT = 1527;

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      String instance = null;
      String host = null;

      final DBInfo dbInfo = builder.build();
      if (dbInfo.getUser() == null) {
        builder.user(DEFAULT_USER);
      }

      final String derbyUrl = jdbcUrl.substring("derby:".length());
      int delimIndex = derbyUrl.indexOf(';');
      final String details = delimIndex >= 0 ? derbyUrl.substring(0, delimIndex) : derbyUrl;
      final String urlPart2 = delimIndex >= 0 ? derbyUrl.substring(delimIndex + 1) : null;

      if (urlPart2 != null) {
        populateStandardProperties(builder, splitQuery(urlPart2, ';'));
      }

      if (details.startsWith("memory:")) {
        builder.subtype("memory");
        final String urlInstance = details.substring("memory:".length());
        if (!urlInstance.isEmpty()) {
          instance = urlInstance;
        }
      } else if (details.startsWith("directory:")) {
        builder.subtype("directory");
        final String urlInstance = details.substring("directory:".length());
        if (!urlInstance.isEmpty()) {
          instance = urlInstance;
        }
      } else if (details.startsWith("classpath:")) {
        builder.subtype("classpath");
        final String urlInstance = details.substring("classpath:".length());
        if (!urlInstance.isEmpty()) {
          instance = urlInstance;
        }
      } else if (details.startsWith("jar:")) {
        builder.subtype("jar");
        final String urlInstance = details.substring("jar:".length());
        if (!urlInstance.isEmpty()) {
          instance = urlInstance;
        }
      } else if (details.startsWith("//")) {
        builder.subtype("network");
        if (dbInfo.getPort() == null) {
          builder.port(DEFAULT_PORT);
        }
        String url = details.substring("//".length());
        final int instanceLoc = url.indexOf('/');
        if (instanceLoc >= 0) {
          instance = url.substring(instanceLoc + 1);
          final int protoLoc = instance.indexOf(':');
          if (protoLoc >= 0) {
            instance = instance.substring(protoLoc + 1);
          }
          url = url.substring(0, instanceLoc);
        }
        final int portLoc = url.indexOf(':');
        if (portLoc > 0) {
          host = url.substring(0, portLoc);
          builder.port(Integer.parseInt(url.substring(portLoc + 1)));
        } else {
          host = url;
        }
      } else {
        builder.subtype("directory");
        if (!details.isEmpty()) {
          instance = details;
        }
      }

      if (host != null) {
        builder.host(host);
      }
      return builder.instance(instance);
    }
  },

  /** http://jtds.sourceforge.net/faq.html#urlFormat */
  JTDS("jtds") {
    private static final int DEFAULT_SQL_SERVER_PORT = 1433;
    private static final int DEFAULT_SYBASE_PORT = 7100;

    @Override
    DBInfo.Builder doParse(final String jdbcUrl, final DBInfo.Builder builder) {
      final DBInfo dbInfo = builder.build();

      final int protoLoc = jdbcUrl.indexOf("://");
      final int typeEndLoc = dbInfo.getType().length();
      final String subtype = jdbcUrl.substring(typeEndLoc + 1, protoLoc);

      builder.subtype(subtype);

      if ("sqlserver".equals(subtype)) {
        if (dbInfo.getPort() == null) {
          builder.port(DEFAULT_SQL_SERVER_PORT);
        }
      } else if ("sybase".equals(subtype)) {
        if (dbInfo.getPort() == null) {
          builder.port(DEFAULT_SYBASE_PORT);
        }
      }

      final String details = jdbcUrl.substring(protoLoc + "://".length());

      final int hostEndLoc;
      final int portLoc = details.indexOf(':', typeEndLoc + 1);
      final int dbLoc = details.indexOf('/', typeEndLoc);
      final int paramLoc = details.indexOf(';', dbLoc);

      if (paramLoc > 0) {
        populateStandardProperties(builder, splitQuery(details.substring(paramLoc + 1), ';'));
        if (dbLoc > 0) {
          builder.db(details.substring(dbLoc + 1, paramLoc));
        }
      } else {
        if (dbLoc > 0) {
          builder.db(details.substring(dbLoc + 1));
        }
      }

      if (portLoc > 0) {
        hostEndLoc = portLoc;
        try {
          builder.port(Integer.parseInt(details.substring(portLoc + 1, dbLoc)));
        } catch (final NumberFormatException ignored) {
        }
      } else if (dbLoc > 0) {
        hostEndLoc = dbLoc;
      } else if (paramLoc > 0) {
        hostEndLoc = paramLoc;
      } else {
        hostEndLoc = details.length();
      }

      builder.host(details.substring(0, hostEndLoc));

      return builder;
    }
  },

  REDSHIFT("redshift") {
    @Override
    DBInfo.Builder doParse(String jdbcUrl, DBInfo.Builder builder) {
      builder = GENERIC_URL_LIKE.doParse(jdbcUrl, builder);
      final DBInfo dbInfo = builder.build();
      if (dbInfo.getHost() != null) {
        int firstDotLoc = dbInfo.getHost().indexOf('.');
        if (firstDotLoc > 0) {
          builder.instance(dbInfo.getHost().substring(0, firstDotLoc));
        }
      }
      return builder;
    }
  },

  SNOWFLAKE("snowflake") {
    @Override
    DBInfo.Builder doParse(String jdbcUrl, DBInfo.Builder builder) {
      String url = jdbcUrl;
      if (url.startsWith("jdbc:")) {
        url = url.substring(5);
      }
      return GENERIC_URL_LIKE.doParse(url, builder);
    }
  },

  IRIS("iris") {
    @Override
    DBInfo.Builder doParse(String jdbcUrl, DBInfo.Builder builder) {
      String url = jdbcUrl;
      int firstSlash = url.indexOf('/', "jdbc://iris:/".length());
      int nextSlash = url.indexOf('/', firstSlash + 1);
      if (nextSlash > firstSlash) {
        // strip the options and preserve only the url like part
        url = url.substring(0, nextSlash);
      }
      return GENERIC_URL_LIKE.doParse(url, builder);
    }
  },
  // https://infocenter.sybase.com/help/index.jsp?topic=/com.sybase.infocenter.dc01776.1601/doc/html/san1357754914053.html
  // Sybase TDS
  SYBASE_TDS("sybase") {
    @Override
    DBInfo.Builder doParse(String jdbcUrl, DBInfo.Builder builder) {
      if (jdbcUrl.startsWith("sybase:tds:")) {
        // that uri is opaque so we need to adjust it in order to be parsed with the classical
        // hierarchical way
        return GENERIC_URL_LIKE
            .doParse("sybase://" + jdbcUrl.substring("sybase:tds:".length()), builder)
            .subtype("tds");
      }
      return GENERIC_URL_LIKE.doParse(jdbcUrl, builder);
    }
  },
  ;

  private static final Map<String, JDBCConnectionUrlParser> typeParsers = new HashMap<>();

  static {
    for (final JDBCConnectionUrlParser parser : JDBCConnectionUrlParser.values()) {
      for (final String key : parser.typeKeys) {
        typeParsers.put(key, parser);
      }
    }
  }

  private static final DDCache<Pair<String, Properties>, DBInfo> CACHED_DB_INFO =
      DDCaches.newFixedSizeCache(32);
  private static final Function<Pair<String, Properties>, DBInfo> PARSE =
      input -> parse(input.getLeft(), input.getRight());

  private final String[] typeKeys;

  JDBCConnectionUrlParser(final String... typeKeys) {
    this.typeKeys = typeKeys;
  }

  abstract DBInfo.Builder doParse(String jdbcUrl, final DBInfo.Builder builder);

  public static DBInfo extractDBInfo(String connectionUrl, Properties props) {
    return CACHED_DB_INFO.computeIfAbsent(Pair.of(connectionUrl, props), PARSE);
  }

  public static DBInfo parse(String connectionUrl, final Properties props) {
    if (connectionUrl == null) {
      return DEFAULT;
    }
    // Make this easier and ignore case.
    connectionUrl = connectionUrl.toLowerCase(Locale.ROOT);

    if (!connectionUrl.startsWith("jdbc:")) {
      return DEFAULT;
    }

    final String jdbcUrl = connectionUrl.substring("jdbc:".length());
    final int typeLoc = jdbcUrl.indexOf(':');

    if (typeLoc < 1) {
      // Invalid format: `jdbc:` or `jdbc::`
      return DEFAULT;
    }

    final String baseType = jdbcUrl.substring(0, typeLoc);

    final DBInfo.Builder parsedProps = DEFAULT.toBuilder().type(baseType);
    populateStandardProperties(parsedProps, props);

    try {
      if (typeParsers.containsKey(baseType)) {
        // Delegate to specific parser
        return typeParsers.get(baseType).doParse(jdbcUrl, parsedProps).build();
      }
      return GENERIC_URL_LIKE.doParse(jdbcUrl, parsedProps).build();
    } catch (final Exception e) {
      ExceptionLogger.LOGGER.debug("Error parsing URL", e);
      return parsedProps.build();
    }
  }

  // Source: https://stackoverflow.com/a/13592567
  @SuppressForbidden
  private static Map<String, String> splitQuery(final String query, final char separator) {
    if (query == null || query.isEmpty()) {
      return Collections.emptyMap();
    }
    final Map<String, String> query_pairs = new LinkedHashMap<>();
    int start = 0;
    for (int i = query.indexOf(separator); start != -1; i = query.indexOf(separator, i + 1)) {
      try {
        final String pair = i >= 0 ? query.substring(start, i) : query.substring(start);
        final int idx = pair.indexOf('=');
        final String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair;
        if (!query_pairs.containsKey(key)) {
          final String value =
              idx > 0 && pair.length() > idx + 1
                  ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                  : null;
          query_pairs.put(key, value);
        }
      } catch (final UnsupportedEncodingException e) {
        // Ignore.
      }
      start = i == -1 ? i : i + 1;
    }
    return query_pairs;
  }

  private static void populateStandardProperties(
      final DBInfo.Builder builder, final Map<?, ?> props) {
    if (props != null && !props.isEmpty()) {
      if (props.containsKey("user")) {
        builder.user((String) props.get("user"));
      }

      if (props.containsKey("databasename")) {
        builder.db((String) props.get("databasename"));
      }
      if (props.containsKey("databaseName")) {
        builder.db((String) props.get("databaseName"));
      }
      if (props.containsKey("db")) {
        builder.db((String) props.get("db"));
      }
      if (props.containsKey("warehouse")) {
        builder.warehouse((String) props.get("warehouse"));
      }
      if (props.containsKey("schema")) {
        builder.schema((String) props.get("schema"));
      }
      if (props.containsKey("servername")) {
        builder.host((String) props.get("servername"));
      }
      if (props.containsKey("serverName")) {
        builder.host((String) props.get("serverName"));
      }

      if (props.containsKey("portnumber")) {
        final String portNumber = (String) props.get("portnumber");
        try {
          builder.port(Integer.parseInt(portNumber));
        } catch (final NumberFormatException e) {
          ExceptionLogger.LOGGER.debug("Error parsing portnumber property: {}", portNumber, e);
        }
      }
      if (props.containsKey("servicename")) {
        // this property is used to specify the db to use for Sybase connection strings
        builder.instance((String) props.get("servicename"));
      }

      if (props.containsKey("portNumber")) {
        final String portNumber = (String) props.get("portNumber");
        try {
          builder.port(Integer.parseInt(portNumber));
        } catch (final NumberFormatException e) {
          ExceptionLogger.LOGGER.debug("Error parsing portNumber property: {}", portNumber, e);
        }
      }
    }
  }
}
