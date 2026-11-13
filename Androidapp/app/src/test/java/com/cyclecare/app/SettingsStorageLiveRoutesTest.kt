package com.cyclecare.app
import com.cyclecare.app.data.local.rust.RustTrackNative
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
/** Run original complete witness bodies with strict executor warmed BEFORE
 * fixture publication/transaction. Originals remain byte-for-byte unchanged. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class SettingsStorageLiveRoutesTest {
 private fun original(type:Class<*>,names:List<String>?=null){
  RustTrackNative.warmUp();check(RustTrackNative.completionReady())
  val methods=type.methods.filter{it.isAnnotationPresent(Test::class.java)&&(names==null||it.name in names)}.sortedBy{it.name};check(methods.isNotEmpty());if(names!=null)check(methods.map{it.name}.toSet()==names.toSet())
  for(method in methods){val fixture=type.getDeclaredConstructor().newInstance();try{type.methods.filter{it.isAnnotationPresent(Before::class.java)}.forEach{it.invoke(fixture)};try{method.invoke(fixture)}catch(e:java.lang.reflect.InvocationTargetException){throw e.cause!!}}finally{type.methods.filter{it.isAnnotationPresent(After::class.java)}.forEach{it.invoke(fixture)}}}
 }
 @Test fun allOriginalSettingsTokenReplacementRowsAndLeaseWitnessesWithNativeExecutorReady(){original(SettingsBoundPortBindingTest::class.java)}
 @Test fun originalQueuedStartupCreatesSettingsWithNativeExecutorReady(){original(StageGProductionStartupRegressionTest::class.java,listOf("androidQueuedAdmissionFirstInstallPublishesLocalAuthAndKeepsItAfterOnboardingWrite","installedBeforeVmStartupPublishesAuthWithoutRevisionRefresh"))}
}
