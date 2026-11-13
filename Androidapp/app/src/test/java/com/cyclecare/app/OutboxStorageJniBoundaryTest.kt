package com.cyclecare.app
import com.cyclecare.app.data.local.rust.RustTrackNative
import org.junit.Assert.*
import org.junit.Test
class OutboxStorageJniBoundaryTest {
 open class Probe(val cut:Int=0,val failure:Throwable=IllegalStateException("sentinel"),val b:Long=7,val s:Long=8,val a:Int=2,val match:Boolean=true,val cas:Boolean=true){
  val trace=mutableListOf<String>();private val thread=Thread.currentThread()
  private fun hit(s:String){assertSame(thread,Thread.currentThread());trace+=s;if(trace.size==cut)throw failure}
  open fun ready(){hit("ready")}
  fun base():Long{hit("base");return b}
  fun acknowledged():Long{hit("acknowledged");return s}
  fun matches():Boolean{hit("matches");return match}
  fun attempts():Int{hit("attempts");return a}
  fun maxServer(v:Long){hit("max:$v")}
  fun deleteAcked(){hit("delete")}
  fun rebase(v:Long){hit("rebase:$v")}
  fun retryCas(v:Int):Boolean{hit("retry:$v");return cas}
 }
 @Test fun actualJniExactBothOrdersAndEveryThrowableIdentityCutoff(){
  RustTrackNative.warmUp()
  for((mode,expected)in listOf(0 to listOf("ready","base","acknowledged","matches","max:8","delete","rebase:8"),1 to listOf("ready","matches","attempts","retry:3"))){val p=Probe();assertEquals(1,RustTrackNative.outboxDriver(p,mode));assertEquals(expected,p.trace)
   for(error in listOf(IllegalArgumentException("sentinel"),ArithmeticException("sentinel"),kotlinx.coroutines.CancellationException("sentinel"),AssertionError("sentinel")))for(cut in 1..expected.size){val q=Probe(cut,error);assertSame(error,runCatching{RustTrackNative.outboxDriver(q,mode)}.exceptionOrNull());assertEquals(expected.take(cut),q.trace)}
  }
 }
 @Test fun ackOverflowAndExactReceiptBeforeMatchPreserveSignedLongDomain(){
  RustTrackNative.warmUp()
  for(base in listOf(Long.MIN_VALUE,-1L,0L,Long.MAX_VALUE-1)){val p=Probe(b=base,s=base+1);assertEquals(1,RustTrackNative.outboxDriver(p,0));assertEquals(listOf("ready","base","acknowledged","matches","max:${base+1}","delete","rebase:${base+1}"),p.trace)}
  val overflow=Probe(b=Long.MAX_VALUE);val e=runCatching{RustTrackNative.outboxDriver(overflow,0)}.exceptionOrNull();assertTrue(e is ArithmeticException);assertEquals("long overflow",e?.message);assertEquals(listOf("ready","base"),overflow.trace)
  val invalid=Probe(s=9,match=false);val x=runCatching{RustTrackNative.outboxDriver(invalid,0)}.exceptionOrNull();assertTrue(x is IllegalArgumentException);assertEquals("Failed requirement.",x?.message);assertEquals(listOf("ready","base","acknowledged"),invalid.trace)
  val no=Probe(match=false);assertEquals(0,RustTrackNative.outboxDriver(no,0));assertEquals(listOf("ready","base","acknowledged","matches"),no.trace)
 }
 @Test fun retryMatchBeforeAttemptsOverflowAndExactCasOutcomeSignedInt(){
  RustTrackNative.warmUp();val no=Probe(a=Int.MAX_VALUE,match=false);assertEquals(0,RustTrackNative.outboxDriver(no,1));assertEquals(listOf("ready","matches"),no.trace)
  val p=Probe(a=Int.MAX_VALUE);val e=runCatching{RustTrackNative.outboxDriver(p,1)}.exceptionOrNull();assertTrue(e is ArithmeticException);assertEquals("integer overflow",e?.message);assertEquals(listOf("ready","matches","attempts"),p.trace)
  for(a in listOf(Int.MIN_VALUE,-1,0,Int.MAX_VALUE-1))for(cas in listOf(false,true)){val q=Probe(a=a,cas=cas);assertEquals(if(cas)1 else 0,RustTrackNative.outboxDriver(q,1));assertEquals(listOf("ready","matches","attempts","retry:${a+1}"),q.trace)}
 }
 @Test fun invalidModesMissingCallbacksAndNoGlobalMonitor(){
  RustTrackNative.warmUp();for(mode in listOf(-1,2,Int.MAX_VALUE)){val p=Probe();assertTrue(runCatching{RustTrackNative.outboxDriver(p,mode)}.exceptionOrNull()is IllegalArgumentException);assertTrue(p.trace.isEmpty())};assertTrue(runCatching{RustTrackNative.outboxDriver(Any(),0)}.exceptionOrNull()is NoSuchMethodError)
  for(mode in 0..1){val p=object:Probe(){override fun ready(){val latch=java.util.concurrent.CountDownLatch(1);val t=Thread{synchronized(RustTrackNative){latch.countDown()}};t.start();assertTrue(latch.await(2,java.util.concurrent.TimeUnit.SECONDS));t.join();super.ready()}};assertEquals(1,RustTrackNative.outboxDriver(p,mode))}
 }
}
