package app.movia.android.domain.legacy;

import java.net.URI;
import java.util.*;
import org.json.*;

/** Data-only configuration owned by Movia. It cannot enable old disabled services or supply credentials. */
public final class MoviaProviderConfiguration {
    private static final Set<Integer> ACTIVE=new HashSet<>(Arrays.asList(1,4,13,16,17,21,23,28,29,31,32,33));
    private MoviaProviderConfiguration() {}
    static JSONObject validate(JSONObject input)throws JSONException {
        if(input.optInt("schemaVersion",-1)!=1)throw new JSONException("CONFIG_SCHEMA");
        JSONArray rows=input.getJSONArray("providers");
        if(rows.length()>34)throw new JSONException("CONFIG_SIZE");
        Set<Integer> seen=new HashSet<>();JSONArray safe=new JSONArray();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);Object rawId=row.get("id");
            if(!(rawId instanceof Number)||((Number)rawId).doubleValue()!=((Number)rawId).intValue())throw new JSONException("CONFIG_ID");
            int id=((Number)rawId).intValue();
            if(!ACTIVE.contains(id)||!seen.add(id))throw new JSONException("CONFIG_PROVIDER");
            String base=row.getString("base"),name=row.optString("name","");
            if(!base.isEmpty()&&!publicUrl(base))throw new JSONException("CONFIG_DOMAIN");
            if(name.length()>100||name.matches("(?s).*[\\r\\n\\x00].*"))throw new JSONException("CONFIG_NAME");
            JSONArray aliases=row.optJSONArray("aliases");if(aliases==null)aliases=new JSONArray();
            if(aliases.length()>16)throw new JSONException("CONFIG_ALIASES");
            JSONArray valid=new JSONArray();
            for(int j=0;j<aliases.length();j++) {String alias=aliases.getString(j);if(!publicUrl(alias))throw new JSONException("CONFIG_ALIAS");valid.put(alias);}
            safe.put(new JSONObject().put("id",id).put("name",name).put("base",base).put("aliases",valid));
        }
        JSONObject properties=input.optJSONObject("properties"),validProperties=new JSONObject();
        if(properties!=null) {
            // The original search mirror is a public URL, never a license/key/token payload.
            if(properties.length()>1)throw new JSONException("CONFIG_PROPERTIES");
            for(Iterator<String> it=properties.keys();it.hasNext();) {String key=it.next();String value=properties.getString(key);
                if(!key.equals("rezka_s")||!publicUrl(value))throw new JSONException("CONFIG_PROPERTY");validProperties.put(key,value);}
        }
        return new JSONObject().put("schemaVersion",1).put("providers",safe).put("properties",validProperties);
    }
    static boolean publicUrl(String raw) {
        if(raw==null||raw.length()>2048||!LegacyProviderEngine.isMediaUrl(raw)||
            !(raw.startsWith("https://")||raw.startsWith("http://")))return false;
        try {URI uri=new URI(raw);String host=uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getRawQuery()==null&&uri.getRawFragment()==null&&
                !host.endsWith(".local")&&!host.endsWith(".internal");}
        catch(Exception e){return false;}
    }
    static String origin(String raw) {
        try {
            URI uri=new URI(raw);String host=uri.getHost();
            if(raw.length()>512||host==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||
                !(uri.getRawPath()==null||uri.getRawPath().isEmpty()||uri.getRawPath().equals("/")))return null;
            if(raw.equals("http://127.0.0.1:8888")||raw.equals("http://127.0.0.1:8888/"))return "http://127.0.0.1:8888";
            if(!"https".equals(uri.getScheme())||!publicUrl(raw)||!host.contains(".")||!host.matches(".*[A-Za-z].*")||
                !(uri.getPort()==-1||uri.getPort()==443))return null;
            return raw.replaceAll("/$","");
        }catch(Exception e){return null;}
    }
}
