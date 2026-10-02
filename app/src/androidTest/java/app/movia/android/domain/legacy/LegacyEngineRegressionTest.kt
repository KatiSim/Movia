package app.movia.android.domain.legacy

import android.content.Context
import android.os.SystemClock
import android.util.Pair
import app.movia.android.domain.playback.PlaybackRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class LegacyEngineRegressionTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine by lazy { LegacyProviderEngine.get(app) }
    private val loader get() = engine.javaClass.getDeclaredField("loader").apply { isAccessible=true }.get(engine) as ClassLoader
    private val folderClass get() = loader.loadClass("obf.k30")
    private val fileClass get() = loader.loadClass("obf.j30")
    @Suppress("UNCHECKED_CAST") private fun enum(name: String, value: String): Any =
        loader.loadClass(name).enumConstants!!.first { (it as Enum<*>).name==value }
    private fun folder(label: String)=folderClass.getConstructor(String::class.java).newInstance(label)
    private fun child(parent: Any, item: Any) { LegacyProviderEngine.call(parent,"OooO",item) }
    private fun video(parent: Any,label: String,url: String="https://fixture.example/$label.mp4"): Any {
        val file=fileClass.getConstructor(folderClass,loader.loadClass("obf.v41"),String::class.java,String::class.java)
            .newInstance(parent,enum("obf.v41","video"),label,url)
        LegacyProviderEngine.call(parent,"OooO0o",file)
        return file
    }
    private fun flatten(root: Any,s: Int?=null,e: Int?=null): JSONArray {
        val method=engine.javaClass.declaredMethods.first { it.name=="flatten" }.apply { isAccessible=true }
        return method.invoke(engine,root,21,"Fixture",2020,s,e,null,emptyMap<String,String>(),
            "https://provider.example/article",SystemClock.elapsedRealtime()+10_000) as JSONArray
    }
    @Test fun allReferenceProvidersKeepTheirOriginalAvailability() {
        val rows=engine.registry();assertEquals(34,rows.length())
        val active=(0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getBoolean("enabledInReference") }
        assertEquals(setOf(1,4,13,16,17,21,23,28,29,31,32,33),active.map { it.getInt("id") }.toSet())
        active.forEach { assertTrue(it.toString(),it.getBoolean("parserConstructed")) }
        assertEquals(20,(0 until rows.length()).count { rows.getJSONObject(it).getString("status")=="DISABLED_IN_REFERENCE" })
        assertEquals(2,(0 until rows.length()).count { rows.getJSONObject(it).getString("status")=="REMOVED" })
    }
    @Test fun engineTypesAreIsolatedFromMoviaDependencies() {
        assertNotSame(app.classLoader,loader)
        assertSame(loader,loader.loadClass("okhttp3.OkHttpClient").classLoader)
        assertSame(Context::class.java.classLoader,loader.parent)
    }
    @Test fun providerStorageIsSeparateFromMoviaDatabases() {
        val context=engine.javaClass.getDeclaredField("context").apply { isAccessible=true }.get(engine) as Context
        assertTrue(context.getDatabasePath("provider.db").canonicalPath.startsWith(app.filesDir.canonicalPath+"/legacy-provider/databases/"))
        assertNotEquals(app.getDatabasePath("provider.db"),context.getDatabasePath("provider.db"))
        try { context.getDatabasePath("../movia.db");fail("Traversal accepted") } catch (_: SecurityException) {}
    }
    @Test fun moviePlaylistPreservesEveryVoiceAndQuality() {
        val root=folder("")
        for(voice in listOf("Studio A","Studio B")) {
            val voiceFolder=folder(voice);child(root,voiceFolder)
            for(q in listOf("360p","720p","1080p"))video(voiceFolder,q)
        }
        val streams=flatten(root);assertEquals(6,streams.length())
        assertEquals(setOf("Studio A","Studio B"),(0 until streams.length()).map { streams.getJSONObject(it).getString("voice") }.toSet())
        assertEquals(setOf("360p","720p","1080p"),(0 until streams.length()).map { streams.getJSONObject(it).getString("quality") }.toSet())
    }
    @Test fun serialNeverReturnsAnotherSeasonOrEpisode() {
        val root=folder("")
        for(s in 1..2) {val season=folder("Сезон $s");child(root,season)
            for(e in 1..3){val episode=folder("Серия $e");child(season,episode);video(episode,"720p")}}
        val result=flatten(root,2,3);assertEquals(1,result.length())
        assertEquals(2,result.getJSONObject(0).getInt("season"));assertEquals(3,result.getJSONObject(0).getInt("episode"))
    }
    @Test fun episodeNeedsExplicitIdentity() {
        val root=folder("");video(root,"720p")
        assertEquals(0,flatten(root,1,1).length())
    }
    @Test fun serialPlaylistCannotBecomeMovieFallback() {
        val root=folder("Season 2");val ep=folder("Episode 3");child(root,ep);video(ep,"720p")
        assertEquals(0,flatten(root).length())
    }
    @Test fun lazySiblingEpisodeIsNotRequested() {
        var expanded=0;val root=folder("Сезон 1")
        for(e in 1..2) {
            val ep=folder("Серия $e");child(root,ep)
            val callback=loader.loadClass("obf.h30\$OooO00o")
            val proxy=Proxy.newProxyInstance(loader,arrayOf(callback)){_,m,_ ->
                if(m.name=="onParse") {expanded++;val loaded=folder("");video(loaded,"720p");loaded} else null
            }
            val lazy=loader.loadClass("obf.h30").getConstructor(callback).newInstance(proxy)
            LegacyProviderEngine.call(ep,"Oooo0oO",lazy)
        }
        assertEquals(1,flatten(root,1,2).length());assertEquals(1,expanded)
    }
    @Test fun rotatingSignedUrlKeepsVariantIdentity() {
        val root=folder("Studio A");val file=video(root,"720p","https://fixture.example/a.mp4?token=one")
        val first=flatten(root).getJSONObject(0).getString("providerItemId")
        LegacyProviderEngine.call(file,"OoooO","https://fixture.example/a.mp4?token=two")
        assertEquals(first,flatten(root).getJSONObject(0).getString("providerItemId"))
    }
    @Test fun qualityFolderDoesNotOverwriteVoice() {
        val voice=folder("Studio B");val q=folder("1080p");child(voice,q);video(q,"1080p")
        assertEquals("Studio B",flatten(voice).getJSONObject(0).getString("voice"))
    }
    @Test fun qualityIsInheritedWhenTheLeafDoesNotRepeatTheFolderFormat() {
        val root=folder("Studio B");val quality=folder("1080p");child(root,quality);video(quality,"Видео")
        val row=flatten(root).getJSONObject(0)
        assertEquals("1080p",row.getString("quality"));assertEquals("Studio B",row.getString("voice"))
    }
    @Test fun movieTitleIsNotFabricatedAsAVoice() {
        val root=folder("Fixture");video(root,"720p")
        assertEquals("Не указано",flatten(root).getJSONObject(0).getString("voice"))
    }
    @Test fun identicallyLabelledMirrorFilesAreRetained() {
        val root=folder("Studio A")
        video(root,"720p","https://a.example/movie.mp4");video(root,"720p","https://b.example/movie.mp4")
        val rows=flatten(root)
        assertEquals(2,rows.length())
        assertNotEquals(rows.getJSONObject(0).getString("providerItemId"),rows.getJSONObject(1).getString("providerItemId"))
        assertEquals(rows.getJSONObject(0).getString("providerSourceId"),rows.getJSONObject(1).getString("providerSourceId"))
        val decoded=LegacyPlaybackResolver.decode(rows,PlaybackRequest("158","Fixture"))
        assertEquals(2,decoded.size)
    }
    @Test fun bothQualityVoiceFolderOrdersHaveTheSameSemanticVariants() {
        val root=folder("Fixture")
        for(q in listOf("360p","720p","1080p")) {
            val quality=folder(q);child(root,quality)
            for(name in listOf("Studio A","Studio B")) {val voice=folder(name);child(quality,voice);video(voice,"Видео")}
        }
        val rows=flatten(root)
        assertEquals(6,rows.length())
        assertEquals(setOf("360p","720p","1080p"),(0 until rows.length()).map { rows.getJSONObject(it).getString("quality") }.toSet())
        assertEquals(setOf("Studio A","Studio B"),(0 until rows.length()).map { rows.getJSONObject(it).getString("voice") }.toSet())
    }
    @Test fun headersKeepSourceCookiesAndRejectCredentialAndLineInjection() {
        val options=loader.loadClass("obf.is0").getConstructor().newInstance()
        for((key,value) in mapOf("Cookie" to "source=ok","Referer" to "https://provider.example/",
            "Authorization" to "Bearer private","X-Test" to "a\r\nb","Host" to "localhost"))
            LegacyProviderEngine.call(options,"OooO0o0",Pair(key,value))
        val headers=LegacyProviderEngine.headers(options)
        assertEquals(setOf("Cookie","Referer"),headers.keys)
    }
    @Test fun tlsVerifierIsThePlatformDefault() {
        val client=loader.loadClass("obf.rd0").getMethod("OooOoo0").invoke(null)
        val verifier=LegacyProviderEngine.call(client,"hostnameVerifier")
        assertFalse(verifier.javaClass.name.startsWith("obf.rd0"))
        assertTrue(verifier.javaClass.name.contains("OkHostnameVerifier"))
    }
    @Test fun untrustedCertificateIsRejectedByTheOriginalClient() {
        val client=loader.loadClass("obf.rd0").getMethod("OooOoo",Integer::class.java).invoke(null,3)
        val builder=loader.loadClass("okhttp3.Request\$Builder").getConstructor().newInstance()
        LegacyProviderEngine.call(builder,"url","https://127.0.0.1:8898/invalid-certificate")
        val request=LegacyProviderEngine.call(builder,"build")
        val call=LegacyProviderEngine.call(client,"newCall",request)
        val failure=try {
            val response=LegacyProviderEngine.call(call,"execute")
            LegacyProviderEngine.call(response,"close");null
        } catch(error: Exception) { generateSequence<Throwable>(error) { it.cause }.last() }
        assertNotNull("An untrusted TLS endpoint was accepted",failure)
        assertTrue("Expected a certificate rejection, got $failure",
            generateSequence<Throwable>(failure) { it.cause }.any { it is java.security.cert.CertificateException } ||
            failure is java.security.cert.CertPathValidatorException)
    }
    @Test fun configurationBelongsToMoviaAndDoesNotRequireTheOldServer() {
        val raw=app.assets.open("providers/movia-provider-config.json").bufferedReader().use { it.readText() }
        val config=MoviaProviderConfiguration.validate(org.json.JSONObject(raw))
        assertEquals(1,config.getInt("schemaVersion"))
        assertEquals("https://rezka.ag",config.getJSONObject("properties").getString("rezka_s"))
        val active=setOf(1,4,13,16,17,21,23,28,29,31,32,33)
        val providers=config.getJSONArray("providers")
        assertTrue(providers.length()>0)
        for(i in 0 until providers.length())assertTrue(active.contains(providers.getJSONObject(i).getInt("id")))
        try { MoviaProviderConfiguration.validate(org.json.JSONObject("{}"));fail("Old server schema accepted") }
        catch (_: org.json.JSONException) {}
    }
    @Test fun configurationCannotSupplyPrivateOrCredentialedDomains() {
        for(url in listOf("http://127.0.0.1:8899/agent", "http://192.168.1.1/", "https://user:password@provider.example/", "https://router.internal/", "https://provider.example/?token=private")) {
            val config=org.json.JSONObject().put("schemaVersion",1).put("providers",JSONArray().put(
                org.json.JSONObject().put("id",21).put("base",url).put("aliases",JSONArray())))
            try { MoviaProviderConfiguration.validate(config);fail("Unsafe domain accepted: $url") }
            catch (_: org.json.JSONException) {}
        }
    }
    @Test fun configurationCannotEnableAReferenceDisabledProvider() {
        val config=org.json.JSONObject().put("schemaVersion",1).put("providers",JSONArray().put(
            org.json.JSONObject().put("id",0).put("base","https://provider.example").put("aliases",JSONArray())))
        try { MoviaProviderConfiguration.validate(config);fail("Disabled provider enabled") }
        catch (_: org.json.JSONException) {}
    }
    @Test fun configurationOriginIsSeparateFromTheLocalP2pGateway() {
        assertEquals("https://movia.example",MoviaProviderConfiguration.origin("https://movia.example/"))
        assertEquals("http://127.0.0.1:8888",MoviaProviderConfiguration.origin("http://127.0.0.1:8888"))
        for(url in listOf("http://movia.example", "https://192.168.1.1", "https://u:p@movia.example", "https://movia.example/internal", "http://127.0.0.1:8899"))
            assertNull(url,MoviaProviderConfiguration.origin(url))
    }
    @Test fun zonaReloadKeepsTheExactSerialReference() {
        val serial=engine.referenceForReload(4,"159","","Fixture",2,3)
        assertEquals("serial",LegacyProviderEngine.call(serial,"getContentUrl"))
        val preserved=engine.referenceForReload(4,"159","original-series-marker","Fixture",2,3)
        assertEquals("original-series-marker",LegacyProviderEngine.call(preserved,"getContentUrl"))
        assertEquals("159",LegacyProviderEngine.call(preserved,"getArticleUrl"))
    }
    @Test fun movieReloadCannotInventASeriesOrAcceptHalfAnEpisode() {
        val movie=engine.referenceForReload(4,"158","","Fixture",null,null)
        assertEquals("",LegacyProviderEngine.call(movie,"getContentUrl"))
        try {engine.referenceForReload(4,"158","","Fixture",1,null);fail("Half episode accepted")}
        catch (_: IllegalArgumentException) {}
        try {engine.referenceForReload(4,"158","bad\r\nmarker","Fixture",1,1);fail("Line injection accepted")}
        catch (_: IllegalArgumentException) {}
    }
    @Test fun originalPlayerHeadersRemainIndependentOfTheApiSignature() {
        val method=engine.javaClass.getDeclaredMethod("defaultHeaders",Any::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }
        val headers=method.invoke(engine,null,4) as Map<*,*>
        val pairs=loader.loadClass("obf.rd0").getMethod("OooOoO").invoke(null) as List<*>
        val expected=pairs.filterIsInstance<Pair<*,*>>().first { it.first.toString().equals("User-Agent",true) }.second
        assertEquals(expected,headers.entries.first { it.key.toString().equals("User-Agent",true) }.value)
    }
    @Test fun sourceUserAgentOverridesTheDefaultRegardlessOfHeaderCase() {
        val options=loader.loadClass("obf.is0").getConstructor().newInstance()
        LegacyProviderEngine.call(options,"OooO0o0",Pair("user-agent","SourcePlayer/1"))
        val method=engine.javaClass.getDeclaredMethod("defaultHeaders",Any::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }
        val headers=method.invoke(engine,options,21) as Map<*,*>
        val agents=headers.entries.filter { it.key.toString().equals("User-Agent",true) }
        assertEquals(1,agents.size);assertEquals("SourcePlayer/1",agents.single().value)
    }
    @Test fun everyWebPlayerVoiceIsRetainedForSelection() {
        val rows=org.json.JSONArray()
        for(i in 1..6)rows.put(org.json.JSONObject().put("providerOrdinal",21).put("provider","hdrezka")
            .put("providerItemId",i.toString().padStart(32,'0')).put("url","https://provider.example/embed/$i")
            .put("articleUrl","https://provider.example/article").put("kind","webplayer").put("voice","Studio $i"))
        val decoded=LegacyPlaybackResolver.decode(rows,PlaybackRequest("158","Fixture"))
        assertEquals(6,decoded.size)
        assertTrue(decoded.all { it.transport=="web_embed" && it.transportMetadata["legacy_web_player"]=="true" })
    }
    @Test fun exactTitleMatchingRejectsRemakesAndSequels() {
        assertTrue(LegacyProviderEngine.matchesTitle("Интерстеллар","Интерстеллар / Interstellar"))
        assertTrue(LegacyProviderEngine.matchesTitle("Во все тяжкие","Во все тяжкие — Сезон 2"))
        assertFalse(LegacyProviderEngine.matchesTitle("Дюна","Дюна 2"))
        assertFalse(LegacyProviderEngine.matchesTitle("Фильм","Другой фильм"))
    }
    @Test fun fourKQualityIsNormalized() {assertEquals("2160p",LegacyProviderEngine.qualityLabel("4k"))}
    @Test fun qualityFolderAliasesUseTheSamePlaybackAndDownloadHeights() {
        for((label,height) in listOf("Full HD" to 1080,"HD" to 720,"UHD" to 2160,"8K" to 4320,"1920x1080" to 1080))
            assertEquals("${height}p",LegacyProviderEngine.qualityLabel(label))
        assertEquals("",LegacyProviderEngine.qualityLabel("Movie 1999"))
    }
    @Test fun originalHttpMediaDomainIsAllowedWithoutOpeningOtherDomains() {
        val policy=android.security.NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("dl5.vibio.tv"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertFalse(policy.isCleartextTrafficPermitted("api.github.com"))
        assertFalse(policy.isCleartextTrafficPermitted("vibio.tv.attacker.example"))
    }
    @Test fun urlsCannotAccessAppFiles() {assertFalse(LegacyProviderEngine.isMediaUrl("file:///data/data/app.movia.android/files/token"))}
    @Test fun javascriptIsNotMedia() {assertFalse(LegacyProviderEngine.isMediaUrl("javascript:alert(1)"))}
    @Test fun loopbackCannotBeProviderMedia() {assertFalse(LegacyProviderEngine.isMediaUrl("http://127.0.0.1:8899/agent/v1/snapshot"))}
    @Test fun privateSubnetCannotBeProviderMedia() {assertFalse(LegacyProviderEngine.isMediaUrl("http://192.168.1.1/media.mp4"))}
    @Test fun encodedLocalIpFormsCannotBeProviderMedia() {
        for(url in listOf("http://2130706433/a.mp4","http://127.1/a.mp4","http://[::ffff:127.0.0.1]/a.mp4","http://[fc00::1]/a.mp4"))
            assertFalse(url,LegacyProviderEngine.isMediaUrl(url))
    }
    @Test fun credentialsInUrlAreRejected() {assertFalse(LegacyProviderEngine.isMediaUrl("https://user:pass@provider.example/media.mp4"))}
    @Test fun lineBreakInUrlIsRejected() {assertFalse(LegacyProviderEngine.isMediaUrl("https://provider.example/a\r\nb"))}
    @Test fun invalidMagnetIsRejected() {assertFalse(LegacyProviderEngine.isMediaUrl("magnet:?xt=urn:btih:fake"))}
    @Test fun legitimateHlsUrlIsAccepted() {assertTrue(LegacyProviderEngine.isMediaUrl("https://provider.example/master.m3u8?token=source"))}
    @Test fun legacyContextCannotStartActivity() {
        val context=engine.javaClass.getDeclaredField("context").apply { isAccessible=true }.get(engine) as Context
        try { context.startActivity(android.content.Intent());fail("Legacy Activity allowed") } catch (_: SecurityException) {}
    }
    @Test fun legacyContextCannotStartService() {
        val context=engine.javaClass.getDeclaredField("context").apply { isAccessible=true }.get(engine) as Context
        try { context.startService(android.content.Intent());fail("Legacy service allowed") } catch (_: SecurityException) {}
    }
}
