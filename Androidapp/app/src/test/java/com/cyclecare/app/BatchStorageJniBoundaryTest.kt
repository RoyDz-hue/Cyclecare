package com.cyclecare.app
import com.cyclecare.app.data.local.rust.RustTrackNative
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
/** Direct synchronous actual JNI boundary, not a Room execution witness. */
class BatchStorageJniBoundaryTest {
 open class Probe(val cut:Int=0,val failure:Throwable=IllegalStateException("probe"),val receiptCode:Int=0,val invalidAt:Int=-1,val overflowAt:Int=-1) {
  val trace=mutableListOf<String>();val thread=Thread.currentThread()
  private fun hit(s:String){assertSame(thread,Thread.currentThread());trace+=s;if(trace.size==cut)throw failure}
  open fun receipt():Int{hit("receipt");return receiptCode}
  fun validRead(i:Int):Boolean{hit("validRead$i");return i!=invalidAt}
  fun valid(i:Int):Boolean{hit("valid$i");return i!=invalidAt}
  open fun revision(i:Int):Long{hit("revision$i");return if(i==overflowAt)Long.MAX_VALUE else 4+i.toLong()}
  open fun server(i:Int):Long{hit("server$i");return 13+i.toLong()}
  fun domain(i:Int){hit("domain$i")}
  fun revisionWrite(i:Int,n:Long,s:Long){hit("writeRevision$i:$n:$s")}
  fun outboxWrite(i:Int,n:Long,s:Long){hit("outbox$i:$n:$s")}
  fun finish(){hit("finish")}
 }
 @Test fun actualSynchronousJniPreservesEveryThrowableIdentityAndStopsAtEveryBoundary() {
  RustTrackNative.warmUp();val p=Probe();assertEquals(0,RustTrackNative.batchDriver(p,0,2));val expected=listOf("receipt","valid0","valid1","revision0","revision1","server0","domain0","writeRevision0:5:13","outbox0:5:13","server1","domain1","writeRevision1:6:14","outbox1:6:14","finish");assertEquals(expected,p.trace)
  for(error in listOf(IllegalArgumentException("sentinel"),ArithmeticException("sentinel"),CancellationException("sentinel"),AssertionError("sentinel")))for(cut in 1..expected.size){val q=Probe(cut,error);val caught=runCatching{RustTrackNative.batchDriver(q,0,2)}.exceptionOrNull();assertSame("original Throwable must stay pending and escape unchanged",error,caught);assertEquals(expected.take(cut),q.trace)}
 }
 @Test fun actualJniEarlyResultsAndAllOverflowBeforeEffectsNoDecodeFallback() {
  RustTrackNative.warmUp();for(code in listOf(1,2)){val p=Probe(receiptCode=code);assertEquals(code,RustTrackNative.batchDriver(p,0,2));assertEquals(listOf("receipt"),p.trace)}
  for(index in 0..1){val p=Probe(invalidAt=index);assertEquals(3,RustTrackNative.batchDriver(p,0,2));assertEquals(listOf("receipt","valid0","valid1").take(index+2),p.trace);val q=Probe(overflowAt=index);assertTrue(runCatching{RustTrackNative.batchDriver(q,0,2)}.exceptionOrNull() is ArithmeticException);assertFalse(q.trace.any{it.startsWith("server")||it.startsWith("domain")})}
  val p=Probe(receiptCode=5);assertTrue(runCatching{RustTrackNative.batchDriver(p,0,2)}.exceptionOrNull() is IllegalStateException);assertEquals(listOf("receipt"),p.trace)
  assertTrue(runCatching{RustTrackNative.batchDriver(Any(),0,2)}.exceptionOrNull() is NoSuchMethodError)
 }
 @Test fun executorHoldsNoGlobalNativeMonitorDuringCallback(){
  RustTrackNative.warmUp();val p=object:Probe(){override fun receipt():Int{val acquired=java.util.concurrent.CountDownLatch(1);val t=Thread{synchronized(RustTrackNative){acquired.countDown()}};t.start();assertTrue("no global bridge monitor held across callbacks",acquired.await(2,java.util.concurrent.TimeUnit.SECONDS));t.join();return super.receipt()}};assertEquals(0,RustTrackNative.batchDriver(p,0,2))
 }

 @Test fun boundShapeReadOrderSingleMutationAndInvalidCountBeforeCallback(){
  RustTrackNative.warmUp()
  for(reads in listOf(0,1,2,16))for(count in listOf(1,2,16)){val p=Probe();assertEquals(0,RustTrackNative.batchDriver(p,reads,count));val expected=mutableListOf("receipt");for(i in 0 until reads)expected+="validRead$i";for(i in 0 until count)expected+="valid$i";for(i in 0 until count)expected+="revision$i";for(i in 0 until count){expected+="server$i";expected+="domain$i";expected+="writeRevision$i:${5+i}:${13+i}";expected+="outbox$i:${5+i}:${13+i}"};expected+="finish";assertEquals(expected,p.trace)
   for(cut in 1..expected.size){val error=kotlinx.coroutines.CancellationException("shape cutoff");val q=Probe(cut,error);assertSame(error,runCatching{RustTrackNative.batchDriver(q,reads,count)}.exceptionOrNull());assertEquals(expected.take(cut),q.trace)}
  }
  for(shape in listOf(-1 to 1,17 to 1,0 to 0,0 to 17)){val p=Probe();assertTrue(runCatching{RustTrackNative.batchDriver(p,shape.first,shape.second)}.exceptionOrNull() is IllegalStateException);assertTrue(p.trace.isEmpty())}
  val p=Probe(invalidAt=0);assertEquals(3,RustTrackNative.batchDriver(p,1,1));assertEquals(listOf("receipt","validRead0"),p.trace)
 }

 @Test fun invalidNegativeRevisionOrServerThrowsBeforeLaterEffects(){
  RustTrackNative.warmUp()
  for(index in 0..15){val p=object:Probe(){override fun revision(i:Int):Long{val value=super.revision(i);return if(i==index)-1 else value}};assertTrue(runCatching{RustTrackNative.batchDriver(p,16,16)}.exceptionOrNull()is IllegalStateException);assertFalse(p.trace.any{it.startsWith("server")||it.startsWith("domain")});val q=object:Probe(){override fun server(i:Int):Long{val value=super.server(i);return if(i==index)-1 else value}};assertTrue(runCatching{RustTrackNative.batchDriver(q,16,16)}.exceptionOrNull()is IllegalStateException);assertFalse(q.trace.any{it=="domain$index"||it=="server${index+1}"})}
 }

}
