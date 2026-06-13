package com.chenweikeng.imf.nra.config;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the per-ride integer maps ({@link ConfigSetting#rideGoalOverrides}, {@link
 * ConfigSetting#advanceNoticeSeconds}) where the presence of an entry means the feature is active
 * for that ride. Keeps only positive numeric values, so stale zeros never surface as rows in the
 * exceptions-only editors. Goal overrides written by versions up to 3.1.5 stored enum tier names;
 * those are migrated ("K1"…"K100" to their numeric value) and "USE_SYSTEM" entries are dropped,
 * since an absent entry already means "use the system goal". Always writes plain numbers.
 */
public final class RideIntMapAdapter extends TypeAdapter<Map<String, Integer>> {
  private static final Map<String, Integer> LEGACY_GOAL_TIERS =
      Map.of("K1", 1000, "K5", 5000, "K10", 10000, "K20", 20000, "K50", 50000, "K100", 100000);

  @Override
  public Map<String, Integer> read(JsonReader in) throws IOException {
    Map<String, Integer> result = new HashMap<>();
    if (in.peek() == JsonToken.NULL) {
      in.nextNull();
      return result;
    }
    in.beginObject();
    while (in.hasNext()) {
      String ride = in.nextName();
      JsonToken token = in.peek();
      if (token == JsonToken.NUMBER) {
        int value = in.nextInt();
        if (value > 0) {
          result.put(ride, value);
        }
      } else if (token == JsonToken.STRING) {
        Integer legacy = LEGACY_GOAL_TIERS.get(in.nextString());
        if (legacy != null) {
          result.put(ride, legacy);
        }
      } else {
        in.skipValue();
      }
    }
    in.endObject();
    return result;
  }

  @Override
  public void write(JsonWriter out, Map<String, Integer> value) throws IOException {
    if (value == null) {
      out.nullValue();
      return;
    }
    out.beginObject();
    for (Map.Entry<String, Integer> entry : value.entrySet()) {
      out.name(entry.getKey()).value(entry.getValue());
    }
    out.endObject();
  }
}
