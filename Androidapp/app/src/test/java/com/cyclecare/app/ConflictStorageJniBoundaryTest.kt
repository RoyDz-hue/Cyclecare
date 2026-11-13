package com.cyclecare.app
import com.cyclecare.app.data.local.rust.RustTrackNative
import org.junit.Assert.*
import org.junit.Test
class ConflictStorageJniBoundaryTest {
 open class Probe(val cut:Int=0,val failure:Throwable=IllegalStateException("sentinel"),val matched:Boolean=true){val trace=mutableListOf<String>();private val thread=Thread.currentThread();private fun hit(s:String){assertSame(thread,Thread.currentThread());trace+=s;if(trace.size==cut)throw failure};open fun ready(){hit("ready")};fun matches():Boolean{hit("matches");return matched};fun latest(){hit("latest")};fun write(){hit("write")}}
 @Test fun actualJniOrderEveryThrowableIdentityCutoffAndMismatch(){RustTrackNative.warmUp();val expected=listOf("ready","matches","latest","write");val p=Probe();assertEquals(1,RustTrackNative.conflictDriver(p));assertEquals(expected,p.trace);for(error in listOf(IllegalArgumentException("sentinel"),ArithmeticException("sentinel"),kotlinx.coroutines.CancellationException("sentinel"),AssertionError("sentinel")))for(cut in 1..4){val q=Probe(cut,error);assertSame(error,runCatching{RustTrackNative.conflictDriver(q)}.exceptionOrNull());assertEquals(expected.take(cut),q.trace)};val q=Probe(matched=false);assertEquals(0,RustTrackNative.conflictDriver(q));assertEquals(expected.take(2),q.trace)}
 @Test fun missingCallbackAndNoGlobalMonitor(){RustTrackNative.warmUp();assertTrue(runCatching{RustTrackNative.conflictDriver(Any())}.exceptionOrNull()is NoSuchMethodError);val p=object:Probe(){override fun ready(){val latch=java.util.concurrent.CountDownLatch(1);val t=Thread{synchronized(RustTrackNative){latch.countDown()}};t.start();assertTrue(latch.await(2,java.util.concurrent.TimeUnit.SECONDS));t.join();super.ready()}};assertEquals(1,RustTrackNative.conflictDriver(p))}
}
