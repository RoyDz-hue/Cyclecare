package com.cyclecare.app
import com.cyclecare.app.data.local.rust.RustTrackNative
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
/** Direct synchronous actual JNI boundary, not a Room execution witness. */
class CompletionStorageJniBoundaryTest {
 open class Probe(val cut:Int=0,val failure:Throwable=IllegalStateException("probe"),val receiptCode:Int=0,val invalidAt:Int=-1,val overflowAt:Int=-1) {
  val trace=mutableListOf<String>();val thread=Thread.currentThread()
  private fun hit(s:String){assertSame(thread,Thread.currentThread());trace+=s;if(trace.size==cut)throw failure}
  open fun receipt():Int{hit("receipt");return receiptCode}
  fun valid(i:Int):Boolean{hit("valid$i");return i!=invalidAt}
  fun revision(i:Int):Long{hit("revision$i");return if(i==overflowAt)Long.MAX_VALUE else 4+i.toLong()}
  fun server(i:Int):Long{hit("server$i");return 13+i.toLong()}
  fun domain(i:Int){hit("domain$i")}
  fun revisionWrite(i:Int,n:Long,s:Long){hit("writeRevision$i:$n:$s")}
  fun outboxWrite(i:Int,n:Long,s:Long){hit("outbox$i:$n:$s")}
  fun finish(a:Long,b:Long){hit("finish:$a:$b")}
 }
 @Test fun actualSynchronousJniPreservesEveryThrowableIdentityAndStopsAtEveryBoundary() {
  RustTrackNative.warmUp();val p=Probe();assertEquals(0,RustTrackNative.completionDriver(p));val expected=listOf("receipt","valid0","valid1","revision0","revision1","server0","domain0","writeRevision0:5:13","outbox0:5:13","server1","domain1","writeRevision1:6:14","outbox1:6:14","finish:5:6");assertEquals(expected,p.trace)
  for(error in listOf(IllegalArgumentException("sentinel"),ArithmeticException("sentinel"),CancellationException("sentinel"),AssertionError("sentinel")))for(cut in 1..expected.size){val q=Probe(cut,error);val caught=runCatching{RustTrackNative.completionDriver(q)}.exceptionOrNull();assertSame("original Throwable must stay pending and escape unchanged",error,caught);assertEquals(expected.take(cut),q.trace)}
 }
 @Test fun actualJniEarlyResultsAndAllOverflowBeforeEffectsNoDecodeFallback() {
  RustTrackNative.warmUp();for(code in listOf(1,2)){val p=Probe(receiptCode=code);assertEquals(code,RustTrackNative.completionDriver(p));assertEquals(listOf("receipt"),p.trace)}
  for(index in 0..1){val p=Probe(invalidAt=index);assertEquals(3,RustTrackNative.completionDriver(p));assertEquals(listOf("receipt","valid0","valid1").take(index+2),p.trace);val q=Probe(overflowAt=index);assertTrue(runCatching{RustTrackNative.completionDriver(q)}.exceptionOrNull() is ArithmeticException);assertFalse(q.trace.any{it.startsWith("server")||it.startsWith("domain")})}
  val p=Probe(receiptCode=5);assertTrue(runCatching{RustTrackNative.completionDriver(p)}.exceptionOrNull() is IllegalStateException);assertEquals(listOf("receipt"),p.trace)
  assertTrue(runCatching{RustTrackNative.completionDriver(Any())}.exceptionOrNull() is NoSuchMethodError)
 }
 @Test fun executorHoldsNoGlobalNativeMonitorDuringCallback(){
  RustTrackNative.warmUp();val p=object:Probe(){override fun receipt():Int{val acquired=java.util.concurrent.CountDownLatch(1);val t=Thread{synchronized(RustTrackNative){acquired.countDown()}};t.start();assertTrue("no global bridge monitor held across callbacks",acquired.await(2,java.util.concurrent.TimeUnit.SECONDS));t.join();return super.receipt()}};assertEquals(0,RustTrackNative.completionDriver(p))
 }

}
