package test;

import java.security.Provider;
import java.security.Provider.Service;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class CryptoUtils {

  HashMap<String, AlgoInfo> getInfos(Class<?> typeClass) {
    HashMap<String, AlgoInfo> infos = new HashMap<>();

    for (Provider prov : Security.getProviders()) {
      String type = typeClass.getSimpleName();
      Set<Service> services = prov.getServices();
      for (Service service : services) {
        if (service.getType().equalsIgnoreCase(type)) {
          AlgoInfo info = infos.get(service.getAlgorithm());
          if (null == info) {
            info = new AlgoInfo();
          }
          info.getProviderNames().add(prov.getName());
          infos.put(service.getAlgorithm().toString(), info);
        }
      }
    }
    for (Map.Entry<String, AlgoInfo> entry : infos.entrySet()) {
      entry.getValue().getAliases().addAll(findAliases(entry.getKey()));
    }
    return infos;
  }

  public List<String> findAliases(String algorithmName) {
    ArrayList<String> aliases = new ArrayList<>();
    for (Provider prov : Security.getProviders()) {
      for (Object key : prov.keySet()) {
        if (prov.get(key).toString().equals(algorithmName)) {
          aliases.add(key.toString());
        }
      }
    }
    return aliases;
  }

  public static void printProviderNames() {
    System.out.println("================================ Providers: ");
    Provider[] providers = Security.getProviders();
    for (Provider provider : providers) {
      System.out.println("Provider:" + provider.getName());
    }
  }

  public static void loadBouncyCastleProvider() {
    Security.addProvider(new BouncyCastleProvider());
  }

  public static void main(String[] args) {
    new CryptoUtils().printAllAlgosAndAliases();
  }

  public void printAllAlgosAndAliases() {
    // loadBouncyCastleProvider();
    for (Map.Entry<String, AlgoInfo> entry : getInfos(Cipher.class).entrySet()) {
      System.out.println(entry.getKey() + " " + entry.getValue());
    }
  }

  public class AlgoInfo {
    ArrayList<String> providerNames = new ArrayList<>();
    ArrayList<String> aliases = new ArrayList<>();

    public ArrayList<String> getProviderNames() {
      return providerNames;
    }

    public ArrayList<String> getAliases() {
      return aliases;
    }

    public String toString() {
      if (aliases.isEmpty()) return "";
      return " Aliases: " + aliases.toString();
    }
  }
}
