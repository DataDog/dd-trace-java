package datadog.trace.instrumentation.dubbo_2_7x;

/**
 * @Description
 * @Author liurui
 * @Date 2023/3/29 18:17
 */
public class DubboConstants {

  public static final String TAG_PREFIX = "dubbo_";
  public static final String TAG_SHORT_URL = TAG_PREFIX + "short_url";
  public static final String TAG_URL = TAG_PREFIX + "url";
  public static final String TAG_METHOD = TAG_PREFIX + "method";
  public static final String TAG_VERSION = TAG_PREFIX + "version";
  public static final String TAG_SIDE = TAG_PREFIX + "side";
  public static final String TAG_HOST = TAG_PREFIX + "host";

  public static final String CONSUMER_SIDE = "consumer";
  public static final String PROVIDER_SIDE = "provider";

  public static final String SIDE_KEY = "side";

  public static final String GROUP_KEY = "group";

  public static final String VERSION = "release";

  public static final String META_RESOURCE="org.apache.dubbo.metadata.MetadataService";

}
