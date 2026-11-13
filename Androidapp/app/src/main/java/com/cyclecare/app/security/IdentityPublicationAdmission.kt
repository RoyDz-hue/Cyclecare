package com.cyclecare.app.security

import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

/** Candidate admission shared by raw identity/lifecycle setters and real UI.
 * Every job owns admission until completion, but no JVM monitor during action
 * or subscribers. Jobs name main/IO dispatch where needed. Reentry queues after
 * current publication. A main caller never blocks waiting for a queued job.
 * Candidate whole operations must compose futures, not assume deferred setter
 * visibility. No production constructor selects this protocol yet.
 */
internal class IdentityPublicationAdmission(
 private val main:((()->Unit)->Unit)={it()},
 private val io:((()->Unit)->Unit)={action->fallbackIo.execute{action()}},
 private val cannotBlock:()->Boolean={false},
 private val maxQueued:Int=256
) {
 private val lock=Any()
 private val queue=ArrayDeque<()->Unit>()
 private var running=false
 @Volatile internal var afterEnqueueForTests:((String)->Unit)?=null
 private val idleWaiters=mutableListOf<CompletableFuture<Unit>>()
 /** Quiescence of already queued/active work, not a ban on future enqueue.
  * Call only after producers stop. No waiter blocks MAIN or domain monitors. */
 fun awaitIdle():CompletableFuture<Unit>{
  val future=CompletableFuture<Unit>()
  val idle=synchronized(lock){if(!running&&queue.isEmpty())true else{idleWaiters.add(future);false}}
  if(idle)future.complete(Unit)
  return future
 }
 private val inFinalizer=ThreadLocal<Boolean>()
 private val afterAction=ThreadLocal<MutableList<()->Unit>?>()
 private var epoch=0L
 private val listeners=java.util.concurrent.CopyOnWriteArrayList<()->Unit>()
 private val actionKind=ThreadLocal<String?>()
 private val failures=java.util.concurrent.CopyOnWriteArrayList<(Throwable)->Unit>()
 private var failurePending=false
 @Volatile private var latestFailure:Throwable?=null
 fun onFailure(listener:(Throwable)->Unit):AutoCloseable{failures.add(listener);return AutoCloseable{failures.remove(listener)}}
 /** One reserved, coalesced recovery slot. Failure notification must survive
  * normal queue saturation, but still runs inside serialized main admission.
  */
 private fun reportFailure(error:Throwable){
  latestFailure=error
  val start=synchronized(lock){
   if(failurePending)return
   failurePending=true
   queue.addLast{
    try{main{
     val previous=actionKind.get();actionKind.set("transition")
     try{
      val failure=synchronized(lock){failurePending=false;latestFailure}
      if(failure!=null)failures.forEach{listener->try{listener(failure)}catch(t:Throwable){android.util.Log.e("IdentityAdmission","failure listener failed",t)}}
     }finally{actionKind.set(previous);next()}
    }}catch(t:Throwable){synchronized(lock){failurePending=false};android.util.Log.e("IdentityAdmission","failure dispatch failed",t);next()}
   }
   if(running)false else{running=true;true}
  }
  if(start)next()
 }
 data class Stamp internal constructor(val epoch:Long)
 fun <T> transition(action:()->T):CompletableFuture<T> = enqueue({it()},"transition"){epoch=Math.addExact(epoch,1L);try{action()}finally{notifyTransitions()}}
 fun <T> transitionMain(action:()->T):CompletableFuture<T> = enqueue(main,"transition"){epoch=Math.addExact(epoch,1L);try{action()}finally{notifyTransitions()}}
 fun <T> transitionIo(action:()->T):CompletableFuture<T> = enqueue(::dispatchIo,"transition"){epoch=Math.addExact(epoch,1L);try{action()}finally{notifyTransitions()}}
 fun onTransition(listener:()->Unit):AutoCloseable{
  listeners.add(listener);return AutoCloseable{listeners.remove(listener)}
 }
 private fun notifyTransitions(){listeners.forEach{listener->enqueue(main,"transition"){listener()}.whenComplete{_,error->if(error!=null)android.util.Log.e("IdentityAdmission","transition listener failed",error)}}}
 fun <T> exclusiveIo(action:()->T):CompletableFuture<T> = enqueue(::dispatchIo,"transition"){action()}
 /** Scoped primitive only. Holds queue ownership across IO read -> MAIN paint,
  * but no domain monitor crosses dispatch. Not a fence for unowned DB writers.
  * Deadline suppresses late paint, NOT a timeout that releases live IO ownership.
  */
 enum class ReadPublication { PAINTED, SUPERSEDED, EXPIRED }
 fun <T> readThenPublish(timeoutMillis:Long=5000,stillCurrent:()->Boolean,read:()->T,paint:(T)->Unit):CompletableFuture<ReadPublication>{
  require(timeoutMillis in 1..30000)
  val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
  var rows:kotlin.Result<T>?=null
  var skipped:ReadPublication?=null
  val dispatch:((()->Unit)->Unit)={finish->
   // Dispatch ownership: pending -> acquired OR rejected. Once acquired,
   // a dispatcher throwing after scheduling cannot release live operation.
   val readState=java.util.concurrent.atomic.AtomicInteger(0)
   try{dispatchIo{
    if(readState.compareAndSet(0,1)){
    val previous=actionKind.get();actionKind.set("transition")
    try{
     if(System.nanoTime()>=deadline)skipped=ReadPublication.EXPIRED
     else if(!stillCurrent())skipped=ReadPublication.SUPERSEDED
     else rows=runCatching{read()}
    }catch(error:Throwable){rows=kotlin.Result.failure(error)}
    finally{actionKind.set(previous)}
    // IO has unwound every synchronous transaction/guard before MAIN dispatch.
    val paintState=java.util.concurrent.atomic.AtomicInteger(0)
    try{main{if(paintState.compareAndSet(0,1))finish()}}
    catch(error:Throwable){
     if(paintState.compareAndSet(0,2)){rows=kotlin.Result.failure(error);skipped=null;finish()}
    }
    }
   }}catch(error:Throwable){
    if(readState.compareAndSet(0,2))throw error
    // Acquired IO owns completion; outer enqueue must not advance twice.
   }
  }
  return enqueue(dispatch,"publication"){
   val omitted=skipped
   if(omitted!=null)omitted
   else {
    val value=checkNotNull(rows).getOrThrow()
    if(System.nanoTime()>=deadline)ReadPublication.EXPIRED
    else if(!stillCurrent())ReadPublication.SUPERSEDED
    else{paint(value);ReadPublication.PAINTED}
   }
  }
 }
 fun stamp():CompletableFuture<Stamp> = enqueue({it()},"read"){Stamp(epoch)}
 fun publish(expected:Stamp,stillCurrent:()->Boolean,paint:()->Unit):CompletableFuture<Boolean> = enqueue(main,"publication"){
  val epochCurrent=epoch==expected.epoch
  val allowed=epochCurrent&&stillCurrent()
  StartupRouteDiagnostics.record(StartupRouteDiagnostics.Event.STAMP_RECEIPT,this,epoch,expected.epoch,if(allowed)1 else 0)
  if(!allowed)false else{paint();true}
 }
 /** Off-main callers wait only before taking domain monitors. Main/reentrant
  * callers queue instead, avoiding blocking a paint that must run on main.
  */
 fun mutate(action:()->Unit){
  if(SubscriberAdmission.defer{transition(action)})return
  if(isTransitionAction()){epoch=Math.addExact(epoch,1L);action();return}
  val future=transition(action)
  if(!isOnDrain()&&inFinalizer.get()!=true&&!cannotBlock())future.join()
 }
 /** Value-returning legacy helpers fail closed on a callback/main queue.
  * They must not claim synchronous success for a deferred mutation.
  */
 fun <T> mutateResult(rejected:T,action:()->T):T{
  if(SubscriberAdmission.isSubscriber())return rejected
  if(isTransitionAction()){epoch=Math.addExact(epoch,1L);return action()}
  if(isOnDrain()||inFinalizer.get()==true||cannotBlock())return rejected
  return transition(action).join()
 }
 /** Marks synchronous StateFlow subscriber callbacks even inside a transition.
  * A reentrant raw setter must queue, not inherit transition privilege.
  */
 fun <T> callbacks(action:()->T):T{
  check(isOnDrain())
  val previous=actionKind.get();actionKind.set("publication")
  return try{action()}finally{actionKind.set(previous)}
 }
 fun isOnDrain():Boolean=actionKind.get()!=null
 fun isTransitionAction():Boolean=actionKind.get()=="transition"
 /** An injected executor is a scheduling hint, not permission to run Room
  * on main. Enforce at execution time, including inline/misrouted executors.
  */
 private fun dispatchIo(action:()->Unit){
  io{
   if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())fallbackIo.execute{action()}else action()
  }
 }
 /** Bounded finalizers of THIS admitted action, not a global queue-empty wait.
  * Registered under admission, run after action domain monitors/thread privilege
  * unwind and before the next job. They must not join/reenter admission.
  */
 fun afterCurrentAction(action:()->Unit){
  val actions=afterAction.get()?:error("admitted_action_required")
  check(actions.size<8){"too_many_admission_finalizers"}
  actions.add(action)
 }
 private fun next(){
  var idle:List<CompletableFuture<Unit>> = emptyList()
  val job=synchronized(lock){if(queue.isEmpty()){running=false;idle=idleWaiters.toList();idleWaiters.clear();null}else queue.removeFirst()}
  // Completion callbacks may enqueue: never invoke them under the queue lock.
  idle.forEach{it.complete(Unit)}
  job?.invoke()
 }
 private inner class AdmissionFuture<T>:CompletableFuture<T>(){
  private fun checkAwait(){check(isDone||inFinalizer.get()!=true){"admission_join_from_finalizer"}}
  override fun join():T{checkAwait();return super.join()}
  override fun get():T{checkAwait();return super.get()}
  override fun get(timeout:Long,unit:java.util.concurrent.TimeUnit):T{checkAwait();return super.get(timeout,unit)}
 }
 private fun <T> enqueue(execute:((()->Unit)->Unit),kind:String,action:()->T):CompletableFuture<T>{
  val future=AdmissionFuture<T>()
  val job:()->Unit={
   try{execute{
    val previous=actionKind.get();val oldFinalizers=afterAction.get()
    val finalizers=mutableListOf<()->Unit>();afterAction.set(finalizers);actionKind.set(kind)
    var value:T?=null;var failed:Throwable?=null
    try{if(!future.isCancelled)value=action()}catch(t:Throwable){failed=t;reportFailure(t)}
    finally{
     actionKind.set(previous);afterAction.set(oldFinalizers)
     val wasFinalizing=inFinalizer.get();inFinalizer.set(true)
     try{finalizers.forEach{finish->try{finish()}catch(t:Throwable){if(failed==null)failed=t;reportFailure(t)}}}finally{inFinalizer.set(wasFinalizing)}
     if(!future.isCancelled){if(failed!=null)future.completeExceptionally(failed)else{@Suppress("UNCHECKED_CAST") future.complete(value as T)}}
     next()
    }
   }}catch(t:Throwable){future.completeExceptionally(t);reportFailure(t);next()}
  }
  val start=synchronized(lock){
   check(maxQueued>0)
   if(queue.size>=maxQueued){future.completeExceptionally(IllegalStateException("identity_admission_queue_full"));false}
   else{queue.addLast(job);if(running)false else{running=true;true}}
  }
  if(future.isCompletedExceptionally)reportFailure(IllegalStateException("identity_admission_queue_full"))
  afterEnqueueForTests?.invoke(kind)
  if(start)next()
  return future
 }
 companion object{
  // IO is never an inline fallback: a queued job may drain after MAIN paint.
  private val fallbackIo=java.util.concurrent.Executors.newSingleThreadExecutor{r->Thread(r,"cyclecare-candidate-io").apply{isDaemon=true}}
  fun forAndroid():IdentityPublicationAdmission{
   val handler=android.os.Handler(android.os.Looper.getMainLooper())
   val workers=java.util.concurrent.Executors.newSingleThreadExecutor{r->Thread(r,"cyclecare-identity-io").apply{isDaemon=true}}
   return IdentityPublicationAdmission(
    main={action->if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())action()else check(handler.post{action()}){"main_admission_unavailable"}},
    io={action->workers.execute{action()}},
    cannotBlock={android.os.Looper.myLooper()==android.os.Looper.getMainLooper()}
   )
  }
 }
}

/** Candidate actual-UI flow wrapper, retains the standard StateFlow API. Value
 * updates are serialized and synchronous subscribers have publication privilege
 * only. Caller must still carry data identity from read through compute.
 */
internal class AdmissionStateFlow<T>(initial:T,private val admission:()->IdentityPublicationAdmission?,private val state:kotlinx.coroutines.flow.MutableStateFlow<T> = kotlinx.coroutines.flow.MutableStateFlow(initial),private val normalize:(T)->T={it},private val afterPaint:(T)->Unit={}):kotlinx.coroutines.flow.MutableStateFlow<T> by state{
 // Diagnostic samples precede normalization; direct state assertions remain
 // the oracle for the resulting admitted value. Null in production.
 companion object { @Volatile internal var beforeValuePaintForTests:((Any?,Any?)->Unit)?=null }
 private fun paintValue(value:T){
  try{beforeValuePaintForTests?.invoke(state.value,value)}catch(_:Throwable){}
  state.value=normalize(value)
  try{afterPaint(state.value)}catch(_:Throwable){}
 }
 override var value:T
  get()=state.value
  set(value){
   val gate=admission()
   val origin=PublicationOrigin.capture()
   if(!origin())return
   if(gate==null)paintValue(value)
   else if(gate.isOnDrain())gate.callbacks{paintValue(value)}
   else gate.stamp().thenAccept{stamp->gate.publish(stamp,{origin()}){paintValue(value)}}
  }
 override val replayCache:List<T> get()=state.replayCache
 override val subscriptionCount:kotlinx.coroutines.flow.StateFlow<Int> get()=state.subscriptionCount
 override suspend fun collect(collector:kotlinx.coroutines.flow.FlowCollector<T>):Nothing=state.collect{value->
  if(admission()==null)collector.emit(value)else kotlinx.coroutines.withContext(SubscriberAdmission.Context()){collector.emit(value)}
 }
 override suspend fun emit(value:T){
  val gate=admission();val origin=PublicationOrigin.capture()
  check(origin()){"stale_admission_emit"}
  if(gate==null){state.emit(normalize(value));return}
  kotlinx.coroutines.suspendCancellableCoroutine<Unit>{continuation->
   val stamped=gate.stamp()
   stamped.whenComplete{stamp,stampError->if(stampError!=null){if(continuation.isActive)continuation.resumeWith(Result.failure(stampError))}else gate.publish(stamp,{continuation.isActive&&origin()}){state.value=normalize(value)}.whenComplete{painted,error->
    if(continuation.isActive){if(error!=null)continuation.resumeWith(Result.failure(error))else if(painted)continuation.resumeWith(Result.success(Unit))else continuation.resumeWith(Result.failure(IllegalStateException("stale_admission_emit")))}
   }}
  }
 }
 override fun tryEmit(value:T):Boolean{
  val gate=admission()
  if(!PublicationOrigin.isCurrent())return false
  return if(gate==null)state.tryEmit(normalize(value))else if(gate.isOnDrain()){gate.callbacks{state.value=normalize(value)};true}else false
 }
 override fun compareAndSet(expect:T,update:T):Boolean{
  check(admission()==null){"candidate admission requires scheduled value publication"}
  return state.compareAndSet(expect,update)
 }
 @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
 override fun resetReplayCache(){state.resetReplayCache()}
}

/** Origin carried through coroutine suspension/resumption. It is a predicate
 * over captured identity/lifecycle, never relabeled with the current owner.
 */
internal object PublicationOrigin {
 private val current=ThreadLocal<(() -> Boolean)?>()
 fun capture():()->Boolean=current.get()?:{true}
 fun isCurrent():Boolean=capture()()
 class Context(private val valid:()->Boolean):kotlinx.coroutines.ThreadContextElement<(() -> Boolean)?>,kotlin.coroutines.AbstractCoroutineContextElement(Key){
  companion object Key:kotlin.coroutines.CoroutineContext.Key<Context>
  override fun updateThreadContext(context:kotlin.coroutines.CoroutineContext):(() -> Boolean)?{val old=current.get();current.set(valid);return old}
  override fun restoreThreadContext(context:kotlin.coroutines.CoroutineContext,oldState:(()->Boolean)?){current.set(oldState)}
 }
 fun <T> withOrigin(valid:()->Boolean,action:()->T):T{val old=current.get();current.set(valid);return try{action()}finally{current.set(old)}}
}

/** StateFlow may resume an Unconfined collector after value assignment returns.
 * Guard the collector continuation itself, not only the setter stack.
 */
internal object SubscriberAdmission {
 private val deferred=ThreadLocal<MutableList<()->Unit>?>()
 fun isSubscriber():Boolean=deferred.get()!=null
 fun defer(action:()->Unit):Boolean{val actions=deferred.get()?:return false;actions.add(action);return true}
 class Context:kotlinx.coroutines.ThreadContextElement<MutableList<()->Unit>?>,kotlin.coroutines.AbstractCoroutineContextElement(Key){
  companion object Key:kotlin.coroutines.CoroutineContext.Key<Context>
  override fun updateThreadContext(context:kotlin.coroutines.CoroutineContext):MutableList<()->Unit>?{val old=deferred.get();deferred.set(mutableListOf());return old}
  override fun restoreThreadContext(context:kotlin.coroutines.CoroutineContext,oldState:MutableList<()->Unit>?){val actions=deferred.get().orEmpty();deferred.set(oldState);if(oldState!=null)oldState.addAll(actions)else actions.forEach{it()}}
 }
}

/** Disabled except an explicitly enabled synthetic test. Fixed metadata only:
 * no owner IDs, rows, strings from storage, credentials or exception text.
 * A bounded process-local ring is dumped once by the failed Home assertion.
 */
internal object StartupRouteDiagnostics {
 enum class Event { VM_CAPTURE, ACTIVITY_VM, START_READ, START_READY, START_SCOPE, START_NO_SCOPE,
  START_BEFORE, START_CHECK, START_AFTER, READ_BEGIN, READ_CHECK, READ_RECEIPT,
  UI_AFTER_ASSIGN, STAMP_RECEIPT, INSTALL_OUTCOME }
 private val lock=Any()
 private var enabled=false
 private var sequence=0L
 private var nextId=0
 private val identities=java.util.IdentityHashMap<Any,Int>()
 private val rows=java.util.ArrayDeque<String>()
 private var dumped=false
 fun start(){synchronized(lock){enabled=true;sequence=0;nextId=0;identities.clear();rows.clear();dumped=false}}
 fun stop(){synchronized(lock){enabled=false;identities.clear();rows.clear()}}
 fun isEnabled()=synchronized(lock){enabled}
 fun record(event:Event,instance:Any,vararg flags:Long){
  try{synchronized(lock){
   if(!enabled)return
   val id=identities[instance]?:if(identities.size<64){(++nextId).also{identities[instance]=it}}else 0
   if(rows.size==128)rows.removeFirst()
   rows.addLast("${++sequence} $event instance=$id flags=${flags.joinToString(",")}")
  }}catch(_:Throwable){}
 }
 fun opaqueId(instance:Any?):Long=synchronized(lock){
  if(!enabled||instance==null)0L else (identities[instance]?:if(identities.size<64){(++nextId).also{identities[instance]=it}}else 0).toLong()
 }
 /** Non-consuming identity check for the synthetic Activity witness. */
 fun hasEvent(event:Event,instance:Any):Boolean=synchronized(lock){
  val id=identities[instance]?:return@synchronized false
  rows.any{it.contains(" $event instance=$id flags=")}
 }
 fun dumpOnce():List<String> = synchronized(lock){if(dumped)emptyList() else{dumped=true;rows.toList()}}
}
