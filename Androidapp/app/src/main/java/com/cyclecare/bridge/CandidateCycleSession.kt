package com.cyclecare.bridge

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.domain.pattern.BaselineMetrics
import com.cyclecare.app.domain.pattern.PatternEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.YearMonth

/** Lab-only binding, no production constructor/caller or account/key access.
 * The immutable envelope is the consumer's only source. Close and all command/
 * result commits compete on that SAME StateFlow CAS cell, never legacy VM flows.
 */
internal class CandidateCycleSession(
 val identity:String,val port:Any,val epoch:Long,
 private val source:Source,
 private val scope:CoroutineScope,
 private val native:Native=RequiredNative,
 private val today:LocalDate=LocalDate.now(),
 private val main:((()->Unit)->Unit)={action->check(android.os.Handler(android.os.Looper.getMainLooper()).post{action()})},
 selected:LocalDate=today
) {
 data class Rows(val settings:CycleSettings,val profile:OnboardingProfile?,val logs:List<CycleDayLog>)
 interface Source { suspend fun read():Rows }
 interface Native { fun compute(rows:Rows,today:LocalDate,selected:LocalDate,month:YearMonth):NativeRows }
 data class NativeRows(val hasAnchor:Boolean,val approximate:Boolean,val selected:DayPrediction?,val today:DayPrediction?,val month:Map<String,DayPrediction>)
 data class Projection(val settings:CycleSettings,val selected:LocalDate,val today:LocalDate,val month:YearMonth,
  val logs:Map<String,CycleDayLog>,val native:NativeRows,val baseline:BaselineMetrics)
 data class Envelope(val epoch:Long,val closed:Boolean=false,val request:Long=0,val generation:Long=0,
  val selected:LocalDate,val month:YearMonth,val recovery:String?=null,val loading:Boolean=false,val projection:Projection?=null,
  val completedRequest:Long?=null)
 private val cell=MutableStateFlow(Envelope(epoch,selected=selected,month=YearMonth.from(selected)))
 val state:StateFlow<Envelope> =cell.asStateFlow()
 @Volatile internal var beforeWorkInstallForTests:((Long)->Unit)?=null
 @Volatile internal var beforeResultCasForTests:(()->Unit)?=null
 private val workLock=Any()
 private data class OwnedWork(val request:Long,val job:Job)
 private var work:OwnedWork?=null
 private fun change(reduce:(Envelope)->Envelope):Envelope?{
  while(true){val old=cell.value;if(old.closed)return null;val next=reduce(old);if(cell.compareAndSet(old,next))return next}
 }
 fun start()=retry(epoch)
 fun selectDate(commandEpoch:Long,date:LocalDate){if(commandEpoch!=epoch)return;request{it.copy(selected=date,month=YearMonth.from(date))}}
 fun month(commandEpoch:Long,month:YearMonth){if(commandEpoch!=epoch)return;request{it.copy(month=month)}}
 fun retry(commandEpoch:Long){if(commandEpoch!=epoch)return;request{it}}
 fun close(){
  while(true){val old=cell.value;if(old.closed)return
   if(cell.compareAndSet(old,old.copy(closed=true,projection=null,loading=false,recovery=null))){val oldWork=synchronized(workLock){work.also{work=null}};oldWork?.job?.cancel();return}
  }
 }
 private fun request(intent:(Envelope)->Envelope){
  val ticket=change{intent(it).copy(request=Math.addExact(it.request,1),loading=true,completedRequest=null)}?:return
  // Cancellation saves work but never provides commit authority. Every result
  // rechecks the exact request/epoch/CLOSED in the final CAS below.
  val candidate=scope.launch(Dispatchers.Default,start=CoroutineStart.LAZY){
   val result=try{
    val rows=source.read()
    val calculated=native.compute(rows,today,ticket.selected,ticket.month)
    Result.success(Projection(rows.settings,ticket.selected,today,ticket.month,rows.logs.associateBy{it.dateString}.toMap(),calculated,
      PatternEngine.computeBaseline(rows.settings,rows.logs)))
   }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){Result.failure(error)}
   // One bounded session-owned pending result retries dispatcher rejection.
   // No new external event is required after MAIN returns. CLOSED cancels it.
   var attempt=0
   while(currentCoroutineContext().isActive){
    val now=cell.value;if(now.closed||now.request!=ticket.request||now.completedRequest==ticket.request)return@launch
    try{
     suspendCancellableCoroutine<Unit>{continuation->
      // Executor is a hint. Exactly one callback may acquire this attempt;
      // schedule-then-throw cannot make an acquired callback run twice.
      val dispatch=java.util.concurrent.atomic.AtomicInteger()
      try{main{
       if(dispatch.compareAndSet(0,1)){
        try{
         beforeResultCasForTests?.invoke()
         while(true){
          val old=cell.value
          if(old.closed||old.request!=ticket.request||old.completedRequest==ticket.request)break
          val next=old.copy(completedRequest=ticket.request,generation=Math.addExact(old.generation,1),loading=false,
           recovery=if(result.isFailure)"Local calendar couldn't be read. Try again." else null,
           projection=result.getOrNull())
          if(cell.compareAndSet(old,next))break
         }
         if(continuation.isActive)continuation.resumeWith(Result.success(Unit))
        }catch(error:Throwable){if(continuation.isActive)continuation.resumeWith(Result.failure(error))}
       }
      }}catch(error:Throwable){
       if(dispatch.compareAndSet(0,2)&&continuation.isActive)continuation.resumeWith(Result.failure(error))
      }
     }
     return@launch
    }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){
     // Record failure privately in authoritative cell even if MAIN unavailable.
     change{if(it.request==ticket.request&&it.completedRequest!=ticket.request)it.copy(recovery="Calendar display is waiting for the screen.")else it}
     attempt=(attempt+1).coerceAtMost(5);delay(attempt*100L)
    }
   }
  }
  beforeWorkInstallForTests?.invoke(ticket.request)
  var previous:OwnedWork?=null
  val installed=synchronized(workLock){
   val current=cell.value
   if(current.closed||current.request!=ticket.request)false
   else{previous=work;work=OwnedWork(ticket.request,candidate);true}
  }
  if(installed){previous?.job?.cancel();candidate.start()}else candidate.cancel()
 }
 object RequiredNative:Native {
  override fun compute(rows:Rows,today:LocalDate,selected:LocalDate,month:YearMonth):NativeRows {
   val s=rows.settings;val p=rows.profile
   val state=checkNotNull(CycleScreenStateAdapter.fetchState(s.lastPeriodStartDate,s.averageCycleLength,s.periodDuration,s.lutealPhaseDays,p?.approximatePeriodChoice,p?.cycleLengthOption,today.toString(),selected.toString())){"native_calendar_required"}
   fun DayPredictionModel.app()=DayPrediction(LocalDate.parse(date),dayOfCycle,CyclePhase.valueOf(phase),isPeriod,isFertile,isOvulation,fertilityConfidence,pregnancyChance)
   val inputs=CyclePredictionInputs(s.lastPeriodStartDate,s.averageCycleLength,s.periodDuration,s.lutealPhaseDays,p?.approximatePeriodChoice,p?.cycleLengthOption,today,state.hasCycleAnchor)
   val days=checkNotNull(CycleScreenStateAdapter.fetchMonthPredictions(inputs,month.toString())){"native_month_required"}.mapValues{it.value.app()}.toMap()
   return NativeRows(state.hasCycleAnchor,state.cycleStartIsApproximate,state.selectedDatePrediction?.app(),state.todayPrediction?.app(),days)
  }
 }
}
