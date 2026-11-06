package com.cyclecare.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cyclecare.app.ui.screens.CandidateCycleSessionContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.bridge.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w412dp-h915dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateCycleSessionMountedTest {
 @get:Rule val compose=createComposeRule()
 private val day=LocalDate.of(2024,4,3)
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
 private val file=File.createTempFile("mounted-synthetic-",".json").also{it.delete();CandidateCycleFixtureSource.writeSynthetic(it)}
 @Before fun trace(){org.robolectric.util.ReflectionHelpers.callStaticMethod<Void>(org.robolectric.shadows.ShadowTrace::class.java,"setAppTracingAllowed",org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!,false))}
 @After fun cleanup(){scope.cancel();file.delete()}
 @Test fun mountedHeldResultCloseResumeDetachReattachAndFreshIdentity(){
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val callbacks=LinkedBlockingQueue<()->Unit>()
  val source=object:CandidateCycleSession.Source{override suspend fun read():CandidateCycleSession.Rows{
   entered.complete(Unit);withContext(NonCancellable){release.await()};return CandidateCycleFixtureSource(file).read()
  }}
  val old=CandidateCycleSession("held",Any(),91,source,scope,today=day,main={callbacks.put(it)},selected=day)
  var shown by mutableStateOf<CandidateCycleSession?>(old)
  compose.setContent{CycleCareTheme(darkTheme=false,dynamicColor=false){shown?.let{CandidateCycleSessionContent(it)}}}
  old.start();runBlocking{withTimeout(3000){entered.await()}}
  compose.onNodeWithText("Loading calendar estimates").assertExists()
  release.complete(Unit)
  val acquired=checkNotNull(callbacks.poll(3,TimeUnit.SECONDS))
  compose.runOnIdle{old.close()}
  acquired();acquired();compose.mainClock.advanceTimeBy(200);compose.waitForIdle()
  compose.onNodeWithTag("cycle_screen_content").assertDoesNotExist()
  compose.onNodeWithTag("cycle_session_closed").assertExists()
  Assert.assertEquals(0,old.state.value.generation)
  compose.runOnIdle{shown=null};compose.waitForIdle()
  compose.runOnIdle{shown=old};compose.waitForIdle()
  compose.onNodeWithTag("cycle_session_closed").assertExists()
  val fresh=CandidateCycleSession("fresh",Any(),92,CandidateCycleFixtureSource(file),scope,today=day,main={it()},selected=day)
  compose.runOnIdle{shown=fresh};fresh.start()
  compose.waitUntil(3000){fresh.state.value.completedRequest==1L};compose.waitForIdle()
  compose.onNodeWithTag("cycle_session_92_1").assertExists()
  compose.onNodeWithTag("cycle_screen_content").assertExists()
  Assert.assertTrue(old.state.value.closed);Assert.assertNull(old.state.value.projection)
  fresh.close()
 }
 @Test fun clicksUseExactNativeMonthDayAndRetryTicket(){
  val fail=AtomicBoolean(false)
  val source=object:CandidateCycleSession.Source{override suspend fun read():CandidateCycleSession.Rows{
   if(fail.get())error("synthetic unavailable");return CandidateCycleFixtureSource(file).read()
  }}
  val session=CandidateCycleSession("commands",Any(),93,source,scope,today=day,main={it()},selected=day)
  compose.setContent{CycleCareTheme(darkTheme=false,dynamicColor=false){CandidateCycleSessionContent(session)}}
  fun await(request:Long){compose.waitUntil(3000){session.state.value.completedRequest==request};compose.waitForIdle();Assert.assertEquals(request,session.state.value.generation)}
  try{
   session.start();await(1)
   compose.onNodeWithTag("calendar_day_1").performClick();await(2)
   val selected=session.state.value.projection!!
   Assert.assertEquals(day.withDayOfMonth(1),selected.selected)
   Assert.assertEquals(1,selected.native.selected!!.dayOfCycle)
   Assert.assertTrue(selected.logs.containsKey(selected.selected.toString()))
   compose.onNodeWithTag("next_month_button").performClick();await(3)
   Assert.assertEquals(YearMonth.of(2024,5),session.state.value.projection!!.month)
   Assert.assertEquals(31,session.state.value.projection!!.native.month.size)
   compose.onNodeWithText("May 2024").assertExists()
   fail.set(true);compose.onNodeWithTag("prev_month_button").performClick();await(4)
   compose.onNodeWithText("Try again").assertExists()
   fail.set(false);compose.onNodeWithText("Try again").performClick();await(5)
   Assert.assertEquals(YearMonth.of(2024,4),session.state.value.projection!!.month)
   Assert.assertEquals(1,session.state.value.projection!!.native.selected!!.dayOfCycle)
   compose.onNodeWithTag("cycle_session_93_5").assertExists()
   compose.onNodeWithTag("edit_selected_day_button").performScrollTo().assertIsNotEnabled()
  }finally{session.close()}
 }
}
