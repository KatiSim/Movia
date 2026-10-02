package app.movia.android.domain.legacy;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;
import org.json.*;

/** The pinned 3.466 provider engine. No legacy Activities, player or app workers run. */
public final class LegacyProviderEngine {
    public static final String ENGINE_SHA256 = "637705386744f29445223fded2ad2df6aeb72295cfc9ce327609e7f0c7a72a00";
    public static final String REFERENCE_SHA256 = "1e613dfe176821acf74150c0b134d61635d5cc5063278fe991a4ff8b990306e0";
    private static volatile LegacyProviderEngine instance;
    private final ClassLoader loader;
    private final Context configurationContext;
    private final LegacyContext context;
    private final Class<?> ids, reference, referenceType, folders, files, videoTypes;
    private final Object[] providers;
    private final Class<?> services;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), r -> { Thread t=new Thread(r,"Movia-provider");t.setDaemon(true);return t; },
        new ThreadPoolExecutor.AbortPolicy());
    private volatile JSONObject lastDiagnostics = new JSONObject();
    private volatile long configurationAttemptMs;
    private volatile String configurationStatus="NOT_STARTED";
    private volatile String configurationOrigin;

    public static LegacyProviderEngine get(Context app) throws Exception {
        LegacyProviderEngine ready=instance;
        if(ready!=null)return ready;
        synchronized(LegacyProviderEngine.class) {
            if(instance==null)instance=new LegacyProviderEngine(app.getApplicationContext());
            return instance;
        }
    }
    private LegacyProviderEngine(Context app) throws Exception {
        configurationContext=app;
        File directory=new File(app.getCodeCacheDir(),"provider-engine");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("ENGINE_DIRECTORY");
        File apk=new File(directory,"lazy-"+ENGINE_SHA256+".apk");
        if(!apk.isFile()||!ENGINE_SHA256.equals(sha256(apk))) {
            if(apk.exists()&&!apk.delete())throw new IOException("ENGINE_REPLACE");
            // Android 14+: mark a new DCL file read-only before writing its bytes.
            try(FileOutputStream out=new FileOutputStream(apk)) {
                if(!apk.setReadOnly())throw new IOException("ENGINE_READ_ONLY");
                try(InputStream in=app.getAssets().open("legacy/lazy-playback-engine.apk")) {
                    byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
                }
                out.getFD().sync();
            }
            if(!ENGINE_SHA256.equals(sha256(apk))){apk.delete();throw new SecurityException("ENGINE_INTEGRITY");}
        }
        if(!apk.setReadOnly())throw new IOException("ENGINE_READ_ONLY");
        // A boot parent keeps the original OkHttp/Rx/Jsoup types isolated from Movia.
        loader=new DexClassLoader(apk.getPath(),directory.getPath(),null,Context.class.getClassLoader());
        PackageInfo archive=app.getPackageManager().getPackageArchiveInfo(apk.getPath(),0);
        if(archive==null||archive.applicationInfo==null)throw new IOException("ENGINE_RESOURCES");
        ApplicationInfo info=archive.applicationInfo;
        info.sourceDir=apk.getPath();info.publicSourceDir=apk.getPath();info.uid=app.getApplicationInfo().uid;
        context=new LegacyContext(app,app.getPackageManager().getResourcesForApplication(info),loader,
            new File(app.getFilesDir(),"legacy-provider"),info);
        Class<?> application=loader.loadClass("com.lazycatsoftware.lazymediadeluxe.BaseApplication");
        Object legacyApp=application.getDeclaredConstructor().newInstance();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);
        attach.setAccessible(true);attach.invoke(legacyApp,context);
        Field singleton=application.getDeclaredField("OooO0oO");singleton.setAccessible(true);singleton.set(null,legacyApp);
        ids=loader.loadClass("obf.bv");providers=(Object[])ids.getMethod("values").invoke(null);
        services=loader.loadClass("com.lazycatsoftware.mediaservices.Services");
        reference=loader.loadClass("com.lazycatsoftware.lazymediadeluxe.models.service.OooO0O0");
        referenceType=loader.loadClass(reference.getName()+"$OooO00o");
        folders=loader.loadClass("obf.k30");files=loader.loadClass("obf.j30");videoTypes=loader.loadClass("obf.v41");
        workers.allowCoreThreadTimeOut(true);
    }
    public JSONObject diagnostics(){return lastDiagnostics;}
    public JSONArray registry() throws Exception {
        JSONArray rows=new JSONArray();
        for(Object provider:providers) {
            Object server=server(provider);boolean active=server!=null&&Boolean.TRUE.equals(call(server,"OooOo00"));
            JSONObject row=new JSONObject().put("id",((Enum<?>)provider).ordinal()).put("name",call(provider,"OooO0oo"))
                .put("enabledInReference",active).put("status",server==null?"REMOVED":active?"ENABLED":"DISABLED_IN_REFERENCE");
            if(active) {
                try {Object parser=article(provider,null,"https://example.invalid/probe");row.put("parserConstructed",parser!=null);}
                catch(Exception e){row.put("parserConstructed",false).put("error",errorCode(e));}
            }
            rows.put(row);
        }
        return rows;
    }
    /** Called from the collector's IO thread with a snapshot, never a shared mutable playlist. */
    public interface ProgressListener { void onCandidates(JSONArray streams) throws Exception; }
    public JSONObject discover(String title,Integer year,Integer season,Integer episode,long timeoutMs) throws Exception {
        return discover(title,year,season,episode,timeoutMs,null);
    }
    /** Bound the entire search, but publish each completed provider without waiting for slower ones. */
    public JSONObject discover(String title,Integer year,Integer season,Integer episode,long timeoutMs,ProgressListener listener) throws Exception {
        long start=SystemClock.elapsedRealtime(),deadline=start+Math.min(30000,Math.max(1000,timeoutMs));
        Future<?> configuration=workers.submit(()->refreshConfiguration());
        // The worker sets built-in domains before requesting remote updates. Updates may continue
        // alongside search; a cold, unavailable configuration endpoint must not delay playback.
        try {configuration.get(Math.max(1,Math.min(300,deadline-SystemClock.elapsedRealtime())),TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){configuration.cancel(true);throw e;}
        catch(TimeoutException ignored){}
        catch(Exception ignored){configurationStatus="DEFAULT_DOMAINS_CONFIGURATION_ERROR";}
        CompletionService<JSONObject> completed=new ExecutorCompletionService<>(workers);
        Map<Future<JSONObject>,Integer> pending=new LinkedHashMap<>();
        for(Object provider:providers) {
            Object server=server(provider);
            if(server==null||!Boolean.TRUE.equals(call(server,"OooOo00")))continue;
            int id=((Enum<?>)provider).ordinal();
            try {pending.put(completed.submit(()->discoverProvider(id,title,year,season,episode,deadline)),id);}
            catch(RejectedExecutionException ignored) { /* keep the draining queue bounded */ }
        }
        JSONArray streams=new JSONArray(),statuses=new JSONArray();
        long firstCandidateMs=-1;
        try {
            while(!pending.isEmpty()) {
                long remaining=deadline-SystemClock.elapsedRealtime();
                if(remaining<=0)break;
                Future<JSONObject> future=completed.poll(remaining,TimeUnit.MILLISECONDS);
                if(future==null)break;
                Integer id=pending.remove(future);JSONObject result;
                try {result=future.get();}
                catch(InterruptedException e){throw e;}
                catch(Exception e){result=new JSONObject().put("providerId",id).put("status",errorCode(e));}
                JSONArray found=result.optJSONArray("streams");
                int previousCount=streams.length();
                if(found!=null)for(int j=0;j<found.length()&&streams.length()<512;j++)streams.put(found.get(j));
                result.remove("streams");statuses.put(result);
                if(streams.length()>previousCount) {
                    if(firstCandidateMs<0)firstCandidateMs=SystemClock.elapsedRealtime()-start;
                    lastDiagnostics=new JSONObject().put("status","DISCOVERING")
                        .put("engineVersion","3.466").put("engineSha256",ENGINE_SHA256)
                        .put("providerCount",providers.length).put("activeProviders",12)
                        .put("providers",new JSONArray(statuses.toString())).put("configurationStatus",configurationStatus)
                        .put("candidateCount",streams.length()).put("firstCandidateMs",firstCandidateMs)
                        .put("elapsedMs",SystemClock.elapsedRealtime()-start);
                    if(listener!=null)listener.onCandidates(new JSONArray(streams.toString()));
                }
            }
            for(Integer id:pending.values())statuses.put(new JSONObject().put("providerId",id).put("status","TIMEOUT"));
        } finally {
            for(Future<?> task:pending.keySet())if(!task.isDone())task.cancel(true);
            if(!configuration.isDone())configuration.cancel(true);
            workers.purge();
        }
        JSONObject diagnostics=new JSONObject().put("engineVersion","3.466").put("engineSha256",ENGINE_SHA256)
            .put("providerCount",providers.length).put("activeProviders",12).put("providers",statuses)
            .put("configurationStatus",configurationStatus).put("firstCandidateMs",firstCandidateMs)
            .put("candidateCount",streams.length()).put("elapsedMs",SystemClock.elapsedRealtime()-start);
        lastDiagnostics=diagnostics;
        return new JSONObject().put("streams",streams).put("diagnostics",diagnostics);
    }
    private JSONObject discoverProvider(int id,String title,Integer year,Integer season,Integer episode,long deadline) {
        JSONObject result=new JSONObject();
        try {
            result.put("providerId",id).put("provider",call(providers[id],"OooO0oo"));
            // Transfer the original guest session/cookie setup, without scheduling its workers.
            if(id==1)loader.loadClass("com.lazycatsoftware.mediaservices.playlist.FILMIX_Work")
                .getMethod("OooOOo",Context.class).invoke(null,context);
            if(id==21)loader.loadClass("com.lazycatsoftware.mediaservices.content.HDREZKA_ListArticles")
                .getMethod("requestHdrezkaCookie").invoke(null);
            List<?> found=search(id,title,deadline);int matched=0;JSONArray streams=new JSONArray();
            for(Object item:found) {
                if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline)break;
                if(!matchesTitle(title,string(call(item,"getClearTitle"))))continue;
                Integer listedYear=extractYear(string(call(item,"getYear"))+" "+string(call(item,"getInfoShort")));
                if(year!=null&&listedYear!=null&&!year.equals(listedYear))continue;
                Integer listedSeason=seasonNumber(string(call(item,"getTitle")));
                if(season!=null&&listedSeason!=null&&!season.equals(listedSeason))continue;
                if(++matched>3)break;
                JSONArray parsed=resolveItem(id,item,title,year,season,episode,deadline);
                for(int j=0;j<parsed.length();j++)streams.put(parsed.get(j));
            }
            result.put("searchHits",found.size()).put("matchedItems",matched).put("streams",streams)
                .put("status",streams.length()>0?"RESOLVED":matched>0?"NO_PLAYABLE_EPISODE":"NO_EXACT_MATCH");
        } catch(Throwable e){try{result.put("status",errorCode(e));}catch(JSONException ignored){}}
        return result;
    }
    private List<?> search(int id,String title,long deadline) throws Exception {
        Object config=call(server(providers[id]),"OooOO0o");
        Field field=config.getClass().getDeclaredField("OooO0oO");field.setAccessible(true);Class<?> list=(Class<?>)field.get(config);
        if(list==null)throw new IOException("SEARCH_UNAVAILABLE");
        Object rx=loader.loadClass("obf.zq0").getDeclaredConstructor().newInstance();
        Object parser=list.getConstructor(rx.getClass()).newInstance(rx);
        HashMap<String,String> params=new HashMap<>();params.put("U",string(call(providers[id],"OooOO0")));
        // The original HDRezka UI replaces the sentinel with its search mirror before
        // calling the parser. Joining the sentinel to the article mirror loads its home page.
        if(id==21)params.put("U",string(call(parser,"getSearchRezka")));
        String query=string(call(config,"OooOOOo",title,params));
        if(id!=4&&id!=32&&!query.startsWith("http"))query=join(string(call(providers[id],"OooO0OO")),query);
        if(query.contains("[U]")||query.contains("[S]")||query.length()>8192)throw new IOException("SEARCH_TEMPLATE");
        CountDownLatch completed=new CountDownLatch(1);AtomicReference<List<?>> result=new AtomicReference<>();
        Class<?> callback=loader.loadClass("obf.l9$OooO00o");
        Object receiver=Proxy.newProxyInstance(loader,new Class[]{callback},(p,m,args)->{
            if(m.getName().equals("OooO00o")){result.set(args!=null&&args[0] instanceof List?(List<?>)args[0]:Collections.emptyList());completed.countDown();}
            else if(m.getName().equals("onError"))completed.countDown();
            else if(m.getName().equals("toString"))return "MoviaProviderCallback";
            return null;
        });
        try {
            call(parser,"parseSearchList",query,receiver);
            if(!completed.await(Math.max(1,Math.min(7000,deadline-SystemClock.elapsedRealtime())),TimeUnit.MILLISECONDS))throw new TimeoutException();
            List<?> rows=result.get();if(rows==null)throw new IOException("SEARCH_NETWORK_ERROR");
            return rows.size()>200?rows.subList(0,200):rows;
        } finally {try{call(rx,"OooO00o");}catch(Exception ignored){}}
    }
    private Object server(Object provider)throws Exception{return services.getMethod("getServer",ids).invoke(null,provider);}
    /** The data endpoint belongs to Movia; no old application/configuration server is contacted. */
    public void setConfigurationOrigin(String origin) {
        String valid=MoviaProviderConfiguration.origin(origin);
        if(valid==null)throw new IllegalArgumentException("CONFIGURATION_ORIGIN");
        if(!valid.equals(configurationOrigin)){configurationOrigin=valid;configurationAttemptMs=0;}
    }
    private void applyConfiguration(JSONObject config,Object domains,Class<?> domain)throws Exception {
        JSONArray entries=MoviaProviderConfiguration.validate(config).getJSONArray("providers");
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);int id=entry.getInt("id");Object service=server(providers[id]);
            String base=entry.getString("base");
            if(!base.isEmpty()&&Boolean.TRUE.equals(call(service,"OooO0OO")))
                call(domains,"OooO0o",providers[id],domain.getConstructor(String.class).newInstance(base));
            JSONArray aliases=entry.getJSONArray("aliases");List<String> list=new ArrayList<>();
            for(int j=0;j<aliases.length();j++)list.add(aliases.getString(j));
            if(!list.isEmpty())call(domains,"OooO0o0",providers[id],domain.getConstructor(String.class).newInstance(String.join(",",list)));
        }
        Object properties=loader.loadClass("obf.m").getConstructor().newInstance();
        JSONObject props=MoviaProviderConfiguration.validate(config).getJSONObject("properties");
        for(Iterator<String> it=props.keys();it.hasNext();) {String key=it.next();((Map)properties).put(key,props.getString(key));}
        Object store=loader.loadClass("obf.n").getMethod("OooOOOo").invoke(null);call(store,"OooOO0o",properties);
    }
    private synchronized void refreshConfiguration() {
        long now=SystemClock.elapsedRealtime();
        if(configurationAttemptMs>0&&now-configurationAttemptMs<900000)return;
        configurationAttemptMs=now;
        try {
            Object domains=loader.loadClass("obf.v").getConstructor().newInstance();Class<?> domain=loader.loadClass("obf.u");
            for(Object provider:providers) {
                Object service=server(provider);if(service==null||!Boolean.TRUE.equals(call(service,"OooOo00")))continue;
                String base=string(call(service,"OooOOOO"));if(base.isEmpty())base=string(call(service,"OooO0o0"));
                call(domains,"OooO0o",provider,domain.getConstructor(String.class).newInstance(decodeDomain(base)));
                Object aliases=call(service,"OooO0o");
                if(aliases instanceof String[])call(domains,"OooO0o0",provider,domain.getConstructor(String.class).newInstance(String.join(",",(String[])aliases)));
            }
            try(InputStream in=configurationContext.getAssets().open("providers/movia-provider-config.json")) {
                applyConfiguration(new JSONObject(readBounded(in,65536)),domains,domain);
            }
            Object stored=loader.loadClass("obf.w").getMethod("OooOOOO").invoke(null);call(stored,"OooOO0o",domains);
            configurationStatus="OWN_ASSET";
            String origin=configurationOrigin;if(origin==null)return;
            File cache=new File(context.getFilesDir(),"movia-provider-config-v1.json");JSONObject config=null;
            if(cache.isFile()&&cache.length()<65536&&System.currentTimeMillis()-cache.lastModified()<21600000) {
                try(InputStream in=new FileInputStream(cache)) {JSONObject saved=new JSONObject(readBounded(in,65536));
                    if(origin.equals(saved.optString("origin")))config=MoviaProviderConfiguration.validate(saved.getJSONObject("configuration"));}
                catch(Exception ignored){}
            }
            if(config!=null)configurationStatus="OWN_CACHE";
            else {
                java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(origin+"/api/providers/config").openConnection();
                try {
                    connection.setConnectTimeout(1000);connection.setReadTimeout(1000);connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","Movia/provider-config");
                    if(connection.getResponseCode()==200) {
                        try(InputStream in=connection.getInputStream()){config=MoviaProviderConfiguration.validate(new JSONObject(readBounded(in,65536)));}
                        File temp=new File(cache.getPath()+".tmp");
                        try(FileOutputStream out=new FileOutputStream(temp)){out.write(new JSONObject().put("origin",origin).put("configuration",config).toString().getBytes("UTF-8"));out.getFD().sync();}
                        if(!temp.renameTo(cache))temp.delete();configurationStatus="OWN_SERVER";
                    }
                } catch(Exception ignored){configurationStatus="OWN_ASSET_SERVER_UNAVAILABLE";}
                finally{connection.disconnect();}
            }
            if(config!=null){applyConfiguration(config,domains,domain);call(stored,"OooOO0o",domains);}
        } catch(Exception ignored){configurationStatus="OWN_DEFAULTS_CONFIGURATION_ERROR";}
    }
    private String decodeDomain(String value)throws Exception {
        return string(loader.loadClass("com.lazycatsoftware.lazymediadeluxe.baseurl.BaseUrlWork").getMethod("OooOOo",String.class).invoke(null,value));
    }
    private static String readBounded(InputStream in,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;
        long deadline=SystemClock.elapsedRealtime()+3000;
        while((n=in.read(bytes))!=-1){if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>deadline)throw new InterruptedIOException("CONFIGURATION_TIMEOUT");if(out.size()+n>limit)throw new IOException("CONFIGURATION_TOO_LARGE");out.write(bytes,0,n);}
        return new String(out.toByteArray(),"UTF-8");
    }
    private Object article(Object provider,Object item,String url)throws Exception {
        if(item==null) {
            item=reference.getConstructor(ids,referenceType).newInstance(provider,enumValue(referenceType,"article"));
            call(item,"setArticleUrl",url);
        }
        Object config=call(server(provider),"OooOO0o");Class<?> cls=(Class<?>)call(config,"OooO0O0");
        return cls.getConstructor(reference).newInstance(item);
    }
    /** Used for a known provider item, including a refreshed/expired playlist. */
    public JSONArray resolveArticle(int id,String url,String title,Integer year,Integer season,Integer episode,long timeoutMs)throws Exception {
        if(id<0||id>=providers.length||!Boolean.TRUE.equals(call(server(providers[id]),"OooOo00")))throw new IllegalArgumentException("PROVIDER_DISABLED");
        return resolveArticle(id,url,"",title,year,season,episode,timeoutMs);
    }
    Object referenceForReload(int id,String url,String contentUrl,String title,Integer season,Integer episode)throws Exception {
        if((season==null)!=(episode==null))throw new IllegalArgumentException("EXACT_EPISODE_REQUIRED");
        Object item=reference.getConstructor(ids,referenceType).newInstance(providers[id],enumValue(referenceType,"article"));
        call(item,"setArticleUrl",url);call(item,"setTitle",title);
        String marker=contentUrl==null?"":contentUrl;
        if(marker.length()>8192||marker.indexOf(13)>=0||marker.indexOf(10)>=0||marker.indexOf(0)>=0)throw new IllegalArgumentException("CONTENT_REFERENCE");
        if(id==4&&season!=null&&marker.isEmpty())marker="serial";
        call(item,"setContentUrl",marker);return item;
    }
    public JSONArray resolveArticle(int id,String url,String contentUrl,String title,Integer year,Integer season,Integer episode,long timeoutMs)throws Exception {
        if(id<0||id>=providers.length||server(providers[id])==null||!Boolean.TRUE.equals(call(server(providers[id]),"OooOo00")))throw new IllegalArgumentException("PROVIDER_DISABLED");
        Object item=referenceForReload(id,url,contentUrl,title,season,episode);
        return resolveItem(id,item,title,year,season,episode,SystemClock.elapsedRealtime()+Math.min(30000,timeoutMs));
    }
    private JSONArray resolveItem(int id,Object item,String title,Integer year,Integer season,Integer episode,long deadline)throws Exception {
        Object parser=article(providers[id],item,null),document=null;
        try {
            Object metadata;
            if(Boolean.TRUE.equals(call(parser,"isCustomParse")))metadata=call(parser,"parseCustom");
            else {
                String url=string(call(parser,"getRealArticleUrl"));
                document=call(call(parser,"getOkHttpCookie"),"OooO",url);
                if(document==null)throw new IOException("ARTICLE_NETWORK_ERROR");
                Field doc=findField(parser.getClass(),"mJsoupDoc");doc.setAccessible(true);doc.set(parser,document);
                metadata=call(parser,"parseBase",document);
            }
            if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline)throw new TimeoutException();
            Integer actualYear=extractYear(string(call(parser,"getYear")));
            if(metadata!=null) {
                Integer parsedYear=extractYear(string(metadata.getClass().getField("OooOO0o").get(metadata)));
                if(parsedYear!=null)actualYear=parsedYear;
                String parsedTitle=string(metadata.getClass().getField("OooO0oO").get(metadata));
                if(!parsedTitle.isEmpty()&&!matchesTitle(title,parsedTitle))throw new IOException("IDENTITY_TITLE_MISMATCH");
            }
            if(year!=null&&actualYear!=null&&!year.equals(actualYear))throw new IOException("IDENTITY_YEAR_MISMATCH");
            Object root=call(parser,"parseContent",document,enumValue(videoTypes,"video"));
            if(root==null)return new JSONArray();
            JSONArray rows=flatten(root,id,title,year,season,episode,seasonNumber(string(call(item,"getTitle"))),
                defaultHeaders(call(parser,"getServicePlayerOptions"),id),string(call(item,"getArticleUrl")),deadline);
            String contentUrl=string(call(item,"getContentUrl"));
            if(contentUrl.length()<=8192&&contentUrl.indexOf(13)<0&&contentUrl.indexOf(10)<0&&contentUrl.indexOf(0)<0)
                for(int i=0;i<rows.length();i++)rows.getJSONObject(i).put("contentUrl",contentUrl);
            return rows;
        } finally {try{call(parser,"stopAllTasks");}catch(Exception ignored){}}
    }
    private JSONArray flatten(Object root,int provider,String title,Integer year,Integer season,Integer episode,
        Integer listedSeason,Map<String,String> headers,String articleUrl,long deadline)throws Exception {
        JSONArray result=new JSONArray();Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        walk(root,provider,title,year,season,episode,listedSeason,null,"","","root",headers,articleUrl,deadline,0,visited,result);
        return result;
    }
    private void walk(Object node,int provider,String title,Integer year,Integer wantedSeason,Integer wantedEpisode,
        Integer season,Integer episode,String voice,String inheritedQuality,String branchPath,Map<String,String> inherited,String articleUrl,long deadline,int depth,
        Set<Object> visited,JSONArray result)throws Exception {
        if(node==null||depth>12||visited.size()>=4096||result.length()>=512||!visited.add(node))return;
        if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline)throw new TimeoutException();
        String label=string(call(node,"OooOOoo"));
        Integer s=seasonNumber(label),e=episodeNumber(label);
        if(s!=null){if(wantedSeason!=null&&!wantedSeason.equals(s))return;season=s;}
        if(e!=null){if(wantedEpisode!=null&&!wantedEpisode.equals(e))return;episode=e;}
        if(folders.isInstance(node)) {
            String folderQuality=qualityLabel(label);
            if(!folderQuality.isEmpty())inheritedQuality=folderQuality;
            if(s==null&&e==null&&!label.isEmpty()&&folderQuality.isEmpty()&&
                !matchesTitle(title,label)&&
                !label.matches("(?iu)^(?:видео|video|качество|quality|источники|плейлист|playlist|streams|mirrors?|зеркала)(?:\\s*\\d+)?$"))voice=label;
            Object lazy=call(node,"OooOoo");
            if(lazy!=null) {
                // Only expand the requested season/episode branch, never the entire serial.
                Object loaded=call(lazy,"OooO00o",node);
                if(loaded!=null&&loaded!=node) {
                    walk(loaded,provider,title,year,wantedSeason,wantedEpisode,season,episode,voice,inheritedQuality,branchPath+"/lazy",inherited,articleUrl,deadline,depth+1,visited,result);
                    return;
                }
            }
            List<?> children=(List<?>)call(node,"OooOo0o");
            for(int childIndex=0;childIndex<children.size();childIndex++)walk(children.get(childIndex),provider,title,year,wantedSeason,wantedEpisode,season,episode,voice,inheritedQuality,branchPath+"/"+childIndex,inherited,articleUrl,deadline,depth+1,visited,result);
            return;
        }
        if(!files.isInstance(node))return;
        if(wantedEpisode!=null) {
            // Explicit episode identity is mandatory; a movie or sibling episode cannot be used as fallback.
            if(episode==null||!wantedEpisode.equals(episode))return;
            if(season==null&&Integer.valueOf(1).equals(wantedSeason))season=1;
            if(wantedSeason!=null&&!wantedSeason.equals(season))return;
        } else if(episode!=null||season!=null)return;
        String url=string(call(node,"OooOo0o"));String type=string(call(node,"OooOo0O"));
        if(!isMediaUrl(url))return;
        Map<String,String> requestHeaders=new LinkedHashMap<>(inherited);mergeHeaders(requestHeaders,headers(call(node,"OooOOo0")));
        String quality=qualityLabel(string(call(call(node,"OooOoO"),"OooO0o")));
        if(quality.isEmpty())quality=qualityLabel(string(call(node,"getFormat"))+" "+label);
        if(quality.isEmpty())quality=inheritedQuality;
        if(quality.isEmpty())quality="Не указано";
        String actualVoice=voice.isEmpty()?"Не указано":voice;
        String itemIdentity=articleUrl+"|"+(season==null?"":season)+"|"+(episode==null?"":episode)+"|"+actualVoice+"|"+quality+"|"+label+"|"+branchPath;
        JSONObject row=new JSONObject().put("providerOrdinal",provider).put("provider",call(providers[provider],"OooO0oo"))
            .put("providerItemId",digest(itemIdentity)).put("providerSourceId",digest(articleUrl+"|"+season+"|"+episode)).put("articleUrl",articleUrl).put("url",url).put("voice",actualVoice)
            .put("quality",quality).put("headers",new JSONObject(requestHeaders)).put("title",title).put("year",year)
            .put("season",season).put("episode",episode).put("kind",type).put("label",label);
        JSONArray subtitles=new JSONArray();String subtitle=string(call(node,"OooOo"));
        if(isMediaUrl(subtitle)&&!subtitle.startsWith("magnet:"))subtitles.put(new JSONObject().put("url",subtitle).put("language","und").put("label","Субтитры"));
        row.put("subtitles",subtitles);result.put(row);
    }
    static boolean matchesTitle(String requested,String found) {
        String desired=normalizeTitle(requested);
        for(String alias:found.split("\\s*/\\s*|\\s+\\|\\s+"))if(!desired.isEmpty()&&desired.equals(normalizeTitle(alias)))return true;
        return false;
    }
    static String normalizeTitle(String value) {
        return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё','е')
            .replaceAll("(?iu)\\s*\\(?(?:19|20)\\d{2}\\)?\\s*$","")
            .replaceAll("(?iu)\\s*(?:[-–:·]\\s*)?(?:(?:сезон|season)\\s*\\d+|\\d+\\s*(?:сезон|season))\\s*$","")
            .replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
    }
    static Integer seasonNumber(String label) {return matchNumber(label,"(?iu)(?:сезон|season)\\s*[:#]?\\s*(\\d{1,3})|(?:^|\\s)(\\d{1,3})\\s*(?:сезон|season)|\\bS(\\d{1,3})E\\d{1,4}\\b");}
    static Integer episodeNumber(String label) {return matchNumber(label,"(?iu)(?:серия|эпизод|episode|ep\\.)\\s*[:#]?\\s*(\\d{1,4})|(?:^|\\s)(\\d{1,4})\\s*(?:серия|эпизод)|\\bS\\d{1,3}E(\\d{1,4})\\b");}
    private static Integer matchNumber(String value,String regex){Matcher m=Pattern.compile(regex).matcher(value==null?"":value);if(!m.find())return null;for(int i=1;i<=m.groupCount();i++)if(m.group(i)!=null){int n=Integer.parseInt(m.group(i));return n>0?n:null;}return null;}
    private static Integer extractYear(String value){return matchNumber(value,"\\b((?:19|20)\\d{2})\\b");}
    static String qualityLabel(String value) {
        if(value==null)return "";if(value.matches("(?iu).*\\b(?:4k|uhd|2160p?)\\b.*"))return "2160p";
        Matcher m=Pattern.compile("(?i)(?:^|\\D)(240|360|480|540|576|720|1080|1440|2160)p?(?:$|\\D)").matcher(value);
        return m.find()?m.group(1)+"p":"";
    }
    static boolean isMediaUrl(String value) {
        if(value==null||value.length()>16384||value.matches("(?s).*[\\x00-\\x20\\x7f].*"))return false;
        if(value.startsWith("magnet:?"))return value.matches("(?i).*xt=urn:btih:(?:[a-f0-9]{40}|[a-z2-7]{32})(?:&.*)?");
        try {
            URI uri=new URI(value);String host=uri.getHost();
            if(host==null||host.contains("%"))return false;
            String literal=host.replaceAll("^\\[|\\]$","");
            if(literal.contains(":")||literal.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}")) {
                java.net.InetAddress address=java.net.InetAddress.getByName(literal);
                if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||
                    address.isSiteLocalAddress()||address.isMulticastAddress())return false;
            }
            return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))&&
            uri.getRawUserInfo()==null&&host!=null&&!host.equalsIgnoreCase("localhost")&&!host.endsWith(".localhost")&&
            !host.matches("(?i)(?:127|10|0)\\..*|192\\.168\\..*|172\\.(?:1[6-9]|2\\d|3[01])\\..*|169\\.254\\..*|\\[?(?:::1|fc[0-9a-f]{2}:.*|fd[0-9a-f]{2}:.*|fe80:.*)\\]?");}
        catch(Exception e){return false;}
    }
    static Map<String,String> headers(Object options)throws Exception {
        Map<String,String> safe=new LinkedHashMap<>();if(options==null)return safe;
        Object raw=call(options,"OooO0oo");if(!(raw instanceof Map))return safe;
        for(Map.Entry<?,?> entry:((Map<?,?>)raw).entrySet()) {
            String key=string(entry.getKey()),value=string(entry.getValue());
            if(key.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")&&value.length()<=8192&&!value.matches("(?s).*[\\r\\n\\x00].*")&&
                !key.equalsIgnoreCase("Authorization")&&!key.equalsIgnoreCase("Proxy-Authorization")&&!key.equalsIgnoreCase("Host")&&
                !key.equalsIgnoreCase("Connection")&&!key.equalsIgnoreCase("Content-Length"))safe.put(key,value);
        }
        return safe;
    }
    private Map<String,String> defaultHeaders(Object options,int provider)throws Exception {
        Map<String,String> result=new LinkedHashMap<>();
        Object pairs=loader.loadClass("obf.rd0").getMethod("OooOoO").invoke(null);
        if(pairs instanceof List)for(Object value:(List<?>)pairs) {
            android.util.Pair<?,?> pair=(android.util.Pair<?,?>)value;
            String key=string(pair.first),header=string(pair.second);
            if((key.equalsIgnoreCase("User-Agent")||key.equalsIgnoreCase("Accept")||key.equalsIgnoreCase("Accept-Language"))&&
                header.length()<8192&&!header.matches("(?s).*[\\r\\n\\x00].*"))result.put(key,header);
        }
        mergeHeaders(result,headers(options));
        return result;
    }
    private static void mergeHeaders(Map<String,String> target,Map<String,String> values) {
        for(Map.Entry<String,String> entry:values.entrySet()) {
            target.keySet().removeIf(key->key.equalsIgnoreCase(entry.getKey()));
            target.put(entry.getKey(),entry.getValue());
        }
    }
    private static String join(String base,String path){return base.replaceAll("/$","")+"/"+path.replaceAll("^/","");}
    private static String string(Object value){return value==null?"":String.valueOf(value);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static Object enumValue(Class<?> type,String name){return Enum.valueOf((Class)type,name);}
    static Object call(Object target,String name,Object...args)throws Exception {
        if(target==null)return null;
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())for(Method method:c.getDeclaredMethods()) {
            if(!method.getName().equals(name)||method.getParameterTypes().length!=args.length)continue;
            boolean compatible=true;Class<?>[] types=method.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i])&&!(types[i].isPrimitive()&&args[i] instanceof Number)){compatible=false;break;}
            if(!compatible)continue;
            method.setAccessible(true);return method.invoke(target,args);
        }
        throw new NoSuchMethodException(name);
    }
    private static Field findField(Class<?> c,String name)throws Exception {for(;c!=null;c=c.getSuperclass())try{return c.getDeclaredField(name);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static String errorCode(Throwable e) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        while(e.getCause()!=null&&seen.add(e)&&!seen.contains(e.getCause()))e=e.getCause();
        String message=e.getMessage()==null?"":e.getMessage();
        if(message.contains("CLEARTEXT")||message.contains("cleartext"))return "HTTP_DOMAIN_BLOCKED";
        if(e instanceof java.net.UnknownHostException)return "DNS_UNAVAILABLE";
        if(e instanceof java.net.SocketTimeoutException||e instanceof TimeoutException)return "TIMEOUT";
        if(e instanceof java.security.cert.CertificateException||e instanceof javax.net.ssl.SSLException)return "TLS_REJECTED";
        if(e instanceof InterruptedException)return "CANCELLED";
        return e.getClass().getSimpleName();
    }
    private static String sha256(File file)throws Exception {MessageDigest m=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)m.update(b,0,n);}return hex(m.digest());}
    private static String digest(String value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"))).substring(0,32);}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}

    /** Separate storage namespace; provider discovery cannot launch another app or background service. */
    static final class LegacyContext extends ContextWrapper {
        private final Resources resources;private final ClassLoader loader;private final File root;private final ApplicationInfo info;
        LegacyContext(Context base,Resources r,ClassLoader l,File f,ApplicationInfo i){super(base);resources=r;loader=l;root=f;info=i;root.mkdirs();info.dataDir=root.getPath();}
        public Resources getResources(){return resources;}public AssetManager getAssets(){return resources.getAssets();}
        public Resources.Theme getTheme(){return resources.newTheme();}public Context getApplicationContext(){return this;}
        public ClassLoader getClassLoader(){return loader;}public ApplicationInfo getApplicationInfo(){return info;}
        public String getPackageName(){return "com.lazycatsoftware.lmd";}
        private File dir(String name){File f=new File(root,name);f.mkdirs();return f;}
        private String name(String value){String n=new File(value).getName();if(!n.equals(value)||n.equals(".")||n.equals(".."))throw new SecurityException("PROVIDER_STORAGE_PATH");return n;}
        public File getFilesDir(){return dir("files");}public File getCacheDir(){return dir("cache");}
        public File getCodeCacheDir(){return dir("code-cache");}public File getNoBackupFilesDir(){return dir("no-backup");}
        public File getExternalFilesDir(String type){return dir("external-files");}public File getExternalCacheDir(){return getCacheDir();}
        public File getDir(String n,int mode){return dir("dir-"+name(n));}
        public SharedPreferences getSharedPreferences(String n,int mode){return getBaseContext().getSharedPreferences("legacy_provider_"+name(n),MODE_PRIVATE);}
        public File getDatabasePath(String n){return new File(dir("databases"),name(n));}
        public SQLiteDatabase openOrCreateDatabase(String n,int m,SQLiteDatabase.CursorFactory f){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(n),f);}
        public SQLiteDatabase openOrCreateDatabase(String n,int m,SQLiteDatabase.CursorFactory f,DatabaseErrorHandler e){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(n).getPath(),f,e);}
        public boolean deleteDatabase(String n){return SQLiteDatabase.deleteDatabase(getDatabasePath(n));}
        public FileInputStream openFileInput(String n)throws FileNotFoundException{return new FileInputStream(new File(getFilesDir(),name(n)));}
        public FileOutputStream openFileOutput(String n,int mode)throws FileNotFoundException{return new FileOutputStream(new File(getFilesDir(),name(n)),(mode&MODE_APPEND)!=0);}
        public boolean deleteFile(String n){return new File(getFilesDir(),name(n)).delete();}
        public File getFileStreamPath(String n){return new File(getFilesDir(),name(n));}
        public void startActivity(Intent i){throw new SecurityException("PROVIDER_ACTIVITY_BLOCKED");}
        public void startActivity(Intent i,Bundle b){startActivity(i);}
        public void startActivities(Intent[] i){throw new SecurityException("PROVIDER_ACTIVITY_BLOCKED");}
        public void startActivities(Intent[] i,Bundle b){startActivities(i);}
        public ComponentName startService(Intent i){throw new SecurityException("PROVIDER_SERVICE_BLOCKED");}
        public ComponentName startForegroundService(Intent i){return startService(i);}
        public boolean bindService(Intent i,ServiceConnection c,int f){throw new SecurityException("PROVIDER_SERVICE_BLOCKED");}
        public void sendBroadcast(Intent i){throw new SecurityException("PROVIDER_BROADCAST_BLOCKED");}
        public void sendBroadcast(Intent i,String permission){sendBroadcast(i);}
    }
}
