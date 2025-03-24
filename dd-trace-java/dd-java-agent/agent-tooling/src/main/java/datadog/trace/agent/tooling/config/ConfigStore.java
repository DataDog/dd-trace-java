public String get(String key) {
  Object value = this.apmConfiguration.get(key);
  return String.valueOf(value);
} 