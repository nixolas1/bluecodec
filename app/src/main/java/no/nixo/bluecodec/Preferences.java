package no.nixo.bluecodec;

import android.content.Context;
import android.content.SharedPreferences;

final class Preferences {
  final SharedPreferences store;

  Preferences(Context context) {
    store = context.getSharedPreferences("headsets", Context.MODE_PRIVATE);
  }

  int codec(String address) {
    return store.getInt(address + ".codec", -1);
  }

  String codecName(String address) {
    return store.getString(address + ".codecName", "Not selected");
  }

  boolean enabled(String address) {
    return store.getBoolean(address + ".enabled", false);
  }

  void save(String address, int type, String name) {
    store.edit().putInt(address + ".codec", type).putString(address + ".codecName", name).apply();
  }

  void enable(String address, boolean enabled) {
    store.edit().putBoolean(address + ".enabled", enabled).apply();
  }

  boolean anyEnabled() {
    for (String key : store.getAll().keySet())
      if (key.endsWith(".enabled") && store.getBoolean(key, false)) return true;
    return false;
  }
}
