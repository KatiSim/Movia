package app.movia.android.domain.playback
import org.junit.Assert.*
import org.junit.Test

class PlaybackDataSourceScopeTest {
    @Test fun lateSegmentCreationRetainsFirstMediaSourceProfileAfterSwitch() {
        var selected = StreamRequestProfile(userAgent="UA-A", headers=mapOf("Referer" to "A"))
        val first = PlaybackDataSourceScope(selected, null) { p -> p.userAgent + ":" + p.headersFor("https://example.test/late.ts")["Referer"] }
        selected = StreamRequestProfile(userAgent="UA-B", headers=mapOf("Referer" to "B"))
        val second = PlaybackDataSourceScope(selected, null) { p -> p.userAgent + ":" + p.headersFor("https://example.test/new.ts")["Referer"] }
        assertEquals("UA-B:B",second.create())
        assertEquals("UA-A:A",first.create())
        assertEquals("UA-A:A",first.create())
    }
    @Test fun mutableCallerHeadersDoNotChangeAnExistingPreparation() {
        val headers = mutableMapOf("Referer" to "original")
        val first = PlaybackDataSourceScope(StreamRequestProfile(headers=headers),null) { it.headersFor("https://example.test/x")["Referer"] }
        headers["Referer"]="later"
        assertEquals("original",first.create())
    }
    @Test fun offlinePreparationDoesNotAdoptLaterNetworkFactory() {
        var offline: (() -> String)? = { "offline-file-A" }
        val first = PlaybackDataSourceScope(StreamRequestProfile(), offline) { "network-A" }
        offline = null
        val second = PlaybackDataSourceScope(StreamRequestProfile(), offline) { "network-B" }
        assertEquals("network-B",second.create());assertEquals("offline-file-A",first.create())
    }
    @Test fun eachLazyRequestCreatesItsOwnDelegateWithTheBoundProfile() {
        var count=0
        val source=PlaybackDataSourceScope(StreamRequestProfile(userAgent="owned"),null) { Pair(++count,it.userAgent) }
        assertEquals(Pair(1,"owned"),source.create());assertEquals(Pair(2,"owned"),source.create())
    }
}
