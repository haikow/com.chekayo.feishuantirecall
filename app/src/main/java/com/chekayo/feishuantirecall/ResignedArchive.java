package com.chekayo.feishuantirecall;

import java.io.File;
import java.util.Iterator;
import org.json.JSONObject;

/** 累计离职记录与档案状态共用的合并规则。历史记录只增长，不把临时缺失当作重新入职。 */
final class ResignedArchive {
    static JSONObject combine(JSONObject history, JSONObject profiles, long now) throws Exception {
        JSONObject result = new JSONObject(history.toString());
        Iterator<String> ids = profiles.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject profile = profiles.optJSONObject(id);
            if (profile == null || !profile.optBoolean("is_resigned", false)) continue;
            JSONObject entry = result.optJSONObject(id);
            if (entry == null) {
                entry = new JSONObject();
                long observed = Math.max(profile.optLong("roster_at", 0), profile.optLong("bulk_at", 0));
                if (observed <= 0) observed = now;
                entry.put("first_seen", observed);
            }
            entry.put("id", id);
            for (String key : new String[] {"name", "en_us_name", "tenant_id"}) {
                String value = profile.optString(key, "");
                if (!value.isEmpty()) entry.put(key, value);
            }
            long observed = Math.max(profile.optLong("roster_at", 0), profile.optLong("bulk_at", 0));
            if (observed > entry.optLong("last_seen", 0)) entry.put("last_seen", observed);
            result.put(id, entry);
        }
        return result;
    }

    /** 调用方与原生快照合并共用 PROFILES_LOCK，避免累计文件相互覆盖。 */
    static void update(File profilesFile, JSONObject profiles) throws Exception {
        File file = new File(profilesFile.getParentFile(), "resigned_all.json");
        synchronized (Config.PROFILES_LOCK) {
            JSONObject before = file.isFile() ? new JSONObject(Config.read(file)) : new JSONObject();
            JSONObject next = combine(before, profiles, System.currentTimeMillis());
            if (!before.toString().equals(next.toString())) Config.write(file, next.toString(1));
        }
    }
    private ResignedArchive() {}
}
