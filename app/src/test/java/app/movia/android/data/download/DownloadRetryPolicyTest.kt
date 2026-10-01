package app.movia.android.data.download
import org.junit.Assert.*
import org.junit.Test
class DownloadRetryPolicyTest {
 @Test fun expiredUrlIsRefreshedOnce() { for(code in listOf(401,403,410)) { assertEquals(DownloadHttpAction.REFRESH_SOURCE,downloadHttpAction(code,0,false)); assertEquals(DownloadHttpAction.FAIL,downloadHttpAction(code,0,true)) } }
 @Test fun networkFailuresHaveBoundedRetries() { for(code in listOf(408,429,500,503,599)) { assertEquals(DownloadHttpAction.RETRY,downloadHttpAction(code,1,true)); assertEquals(DownloadHttpAction.FAIL,downloadHttpAction(code,2,true)) } }
 @Test fun permanentNotFoundDoesNotRetry() { assertEquals(DownloadHttpAction.FAIL,downloadHttpAction(404,0,false)) }
 @Test fun retryAfterIsBoundedAndUnknownIsSafe() { assertEquals(2000L,boundedRetryAfterMillis("2")); assertEquals(300000L,boundedRetryAfterMillis("999999999999")); assertEquals(0L,boundedRetryAfterMillis("-5"));assertEquals(0L,boundedRetryAfterMillis("garbage")) }
}
