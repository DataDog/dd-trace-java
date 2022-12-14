package datadog.trace.api;

public class NamingSchema {

  public static VersionedSchema get() {
    final int version = Config.get().getNamingSchemaVersion();
    switch (version) {
      case 0:
        return V0.INSTANCE;
      case 1:
        return V1.INSTANCE;
      default:
        throw new IllegalArgumentException("Unsupported naming schema version " + version);
    }
  }

  public interface WithNaming {
    String operationName();

    String serviceName();
  }

  public interface OtherSchema {
    WithNaming aws(final String ddService, final String awsService);
  }

  public interface HttpSchema {
    WithNaming inbound(final String ddService);

    WithNaming outbound(final String ddService);
  }

  public interface MessagingSchema {
    InboundMessagingSchema inbound();

    OutboundMessagingSchema outbound();
  }

  public interface InboundMessagingSchema {
    WithNaming kafka(final String ddService);
  }

  public interface OutboundMessagingSchema {
    WithNaming kafka(final String ddService);

    WithNaming sns(final String ddService);
  }

  public interface StorageSchema {
    WithNaming redis(final String ddService);

    WithNaming jdbc(final String ddService, final String jdbcDriver);
  }

  public interface VersionedSchema {
    MessagingSchema messaging();

    StorageSchema storage();

    HttpSchema http();

    OtherSchema other();
  }

  public static class V0 implements VersionedSchema {
    private static final VersionedSchema INSTANCE = new V0();

    public static class Storage implements StorageSchema {
      private static final StorageSchema INSTANCE = new Storage();

      @Override
      public WithNaming redis(String ddService) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return "redis.query";
          }

          @Override
          public String serviceName() {
            return "redis";
          }
        };
      }

      @Override
      public WithNaming jdbc(String ddService, String driverName) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return "database.query";
          }

          @Override
          public String serviceName() {
            return driverName;
          }
        };
      }
    }

    public static class Messaging implements MessagingSchema {
      private static final MessagingSchema INSTANCE = new Messaging();

      private static class Inbound implements InboundMessagingSchema {
        private static final InboundMessagingSchema INSTANCE = new Inbound();

        @Override
        public WithNaming kafka(String ddService) {
          return new WithNaming() {
            @Override
            public String operationName() {
              return "kafka.consume";
            }

            @Override
            public String serviceName() {
              return "kafka";
            }
          };
        }
      }

      private static class Outbound implements OutboundMessagingSchema {
        private static final OutboundMessagingSchema INSTANCE = new Outbound();

        @Override
        public WithNaming kafka(String ddService) {
          return new WithNaming() {
            @Override
            public String operationName() {
              return "kafka.produce";
            }

            @Override
            public String serviceName() {
              return "kafka";
            }
          };
        }

        @Override
        public WithNaming sns(String ddService) {
          return new WithNaming() {
            @Override
            public String operationName() {
              return "SNS.Publish";
            }

            @Override
            public String serviceName() {
              return "sns";
            }
          };
        }
      }

      @Override
      public InboundMessagingSchema inbound() {
        return Inbound.INSTANCE;
      }

      @Override
      public OutboundMessagingSchema outbound() {
        return Outbound.INSTANCE;
      }
    }

    public static class Http implements HttpSchema {
      private static final HttpSchema INSTANCE = new Http();

      @Override
      public WithNaming inbound(String ddService) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return "http.request";
          }

          @Override
          public String serviceName() {
            return ddService;
          }
        };
      }

      @Override
      public WithNaming outbound(String ddService) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return "http.request";
          }

          @Override
          public String serviceName() {
            return ddService;
          }
        };
      }
    }

    public static class Other implements OtherSchema {
      private static final OtherSchema INSTANCE = new Other();

      @Override
      public WithNaming aws(String ddService, String awsService) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return "aws.http";
          }

          @Override
          public String serviceName() {
            return "java-aws-sdk";
          }
        };
      }
    }

    @Override
    public MessagingSchema messaging() {
      return Messaging.INSTANCE;
    }

    @Override
    public StorageSchema storage() {
      return Storage.INSTANCE;
    }

    @Override
    public HttpSchema http() {
      return Http.INSTANCE;
    }

    @Override
    public OtherSchema other() {
      return Other.INSTANCE;
    }
  }

  public static class V1 implements VersionedSchema {
    private static final VersionedSchema INSTANCE = new V1();

    public static class Storage implements StorageSchema {
      private static final StorageSchema INSTANCE = new Storage();

      @Override
      public WithNaming redis(String ddService) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return "redis.command";
          }

          @Override
          public String serviceName() {
            return ddService + "-redis";
          }
        };
      }

      @Override
      public WithNaming jdbc(String ddService, String driverName) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return driverName + ".query";
          }

          @Override
          public String serviceName() {
            return ddService + "-" + driverName;
          }
        };
      }
    }

    public static class Messaging implements MessagingSchema {
      private static final MessagingSchema INSTANCE = new Messaging();

      private static class Inbound implements InboundMessagingSchema {
        private static final InboundMessagingSchema INSTANCE = new Inbound();

        @Override
        public WithNaming kafka(String ddService) {
          return new WithNaming() {
            @Override
            public String operationName() {
              return "kafka.process";
            }

            @Override
            public String serviceName() {
              return ddService;
            }
          };
        }
      }

      private static class Outbound implements OutboundMessagingSchema {
        private static final OutboundMessagingSchema INSTANCE = new Outbound();

        @Override
        public WithNaming kafka(String ddService) {
          return new WithNaming() {
            @Override
            public String operationName() {
              return "kafka.send";
            }

            @Override
            public String serviceName() {
              return ddService;
            }
          };
        }

        @Override
        public WithNaming sns(String ddService) {
          return new WithNaming() {
            @Override
            public String operationName() {
              return "sns.send";
            }

            @Override
            public String serviceName() {
              return ddService;
            }
          };
        }
      }

      @Override
      public InboundMessagingSchema inbound() {
        return Inbound.INSTANCE;
      }

      @Override
      public OutboundMessagingSchema outbound() {
        return Outbound.INSTANCE;
      }
    }

    public static class Http implements HttpSchema {
      private static final HttpSchema INSTANCE = new Http();

      @Override
      public WithNaming inbound(String ddService) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return "http.server.request";
          }

          @Override
          public String serviceName() {
            return ddService;
          }
        };
      }

      @Override
      public WithNaming outbound(String ddService) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return "http.client.request";
          }

          @Override
          public String serviceName() {
            return ddService;
          }
        };
      }
    }

    public static class Other implements OtherSchema {
      private static final OtherSchema INSTANCE = new Other();

      @Override
      public WithNaming aws(String ddService, String awsService) {
        return new WithNaming() {
          @Override
          public String operationName() {
            return awsService + ".request";
          }

          @Override
          public String serviceName() {
            return ddService;
          }
        };
      }
    }

    @Override
    public MessagingSchema messaging() {
      return Messaging.INSTANCE;
    }

    @Override
    public StorageSchema storage() {
      return Storage.INSTANCE;
    }

    @Override
    public HttpSchema http() {
      return Http.INSTANCE;
    }

    @Override
    public OtherSchema other() {
      return Other.INSTANCE;
    }
  }
}
