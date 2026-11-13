package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.rust.*
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reproduces production-generated Room inventory and immediate-main startup,
 * not a fake tracker that silently accepts additive metadata tables. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class StageGProductionStartupRegressionTest {
    private val context:Context = ApplicationProvider.getApplicationContext()
    private fun db() = Room.inMemoryDatabaseBuilder(context,CycleCareDatabase::class.java)
        .allowMainThreadQueries().addCallback(object:androidx.room.RoomDatabase.Callback(){
            override fun onCreate(db:androidx.sqlite.db.SupportSQLiteDatabase){applyRustMetadataDraft(db)}
        }).build()
    private fun prefs() = AuthPreferences(context,context.getSharedPreferences("stage-g-host-${UUID.randomUUID()}",Context.MODE_PRIVATE),true)
        .apply { userId="synthetic-stageg-host";isLocalOnlyMode=true }

    @Test fun registeredDomainWakeRereadsPairedFloatBitsOnActualGeneratedDatabase() = runBlocking {
        val database=db();val prefs=prefs();val host=RustBoundRoomHost(prefs)
        try {
            database.openHelper.writableDatabase
            // Negative control: the SQL table exists but Room did NOT register it.
            database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM rust_float_bits").use{assertTrue(it.moveToFirst())}
            val invalid=object:androidx.room.InvalidationTracker.Observer("rust_float_bits"){
                override fun onInvalidated(tables:Set<String>)=Unit
            }
            try { database.invalidationTracker.addObserver(invalid);fail("metadata must remain outside Room inventory") }
            catch(expected:IllegalArgumentException){assertTrue(expected.message.orEmpty().contains("rust_float_bits"))}
            val token=(host.publish(null,database,prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            val emissions=CopyOnWriteArrayList<RustBoundRoomHost.Result<RustBoundRoomHost.Snapshot>>()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val collector=scope.async { host.observe(token).collect { emissions.add(it) } }
            try {
                withTimeout(10_000){while(emissions.isEmpty()){if(collector.isCompleted)collector.await();delay(20)}}
                val row=CycleDayLog(dateString="2026-11-06",bbtCelsius=-0.0f,sleepHours=-0.0f)
                database.runInTransaction {
                    runBlocking { database.cycleDayLogDao().insertLog(row) }
                    RustFloatRows.recordAfterDomainWrite(database.openHelper.writableDatabase,row)
                }
                database.invalidationTracker.refreshAsync()
                withTimeout(10_000){while(emissions.none{e->e is RustBoundRoomHost.Result.Value && e.value.rows.any{it.dateString==row.dateString}})delay(20)}
                val seen=emissions.filterIsInstance<RustBoundRoomHost.Result.Value<RustBoundRoomHost.Snapshot>>().last().value.rows.single()
                assertEquals((-0.0f).toRawBits(),seen.bbtCelsius.toRawBits())
                assertEquals((-0.0f).toRawBits(),seen.sleepHours.toRawBits())
                // Same numerical SQLite values, different exact float bits:
                // the production UPDATE + sidecar path must still wake Room.
                val positive=row.copy(bbtCelsius=0.0f,sleepHours=0.0f)
                database.runInTransaction {
                    RustDomainRows.write(database.openHelper.writableDatabase,row.dateString,RustDomainRows.Row.Log(positive))
                }
                database.invalidationTracker.refreshAsync()
                withTimeout(10_000){while(emissions.none{e->e is RustBoundRoomHost.Result.Value && e.value.rows.any{it.bbtCelsius.toRawBits()==0 && it.sleepHours.toRawBits()==0}}){if(collector.isCompleted)collector.await();delay(20)}}
                database.runInTransaction { RustDomainRows.write(database.openHelper.writableDatabase,row.dateString,null) }
                database.invalidationTracker.refreshAsync()
                withTimeout(10_000){while(emissions.lastOrNull().let{e->e !is RustBoundRoomHost.Result.Value||e.value.rows.isNotEmpty()}){if(collector.isCompleted)collector.await();delay(20)}}
                database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM rust_float_bits").use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}

            } finally {collector.cancelAndJoin();scope.cancel()}
        } finally {database.close()}
    }

    @Test fun immediateMainStartupInitializesEveryCallbackBackingFieldBeforeListening() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val database=db();val prefs=prefs();val failures=CopyOnWriteArrayList<Throwable>()
        val gate=IdentityPublicationAdmission(main={it()},cannotBlock={false})
        prefs.attachPublicationAdmission(gate)
        val observer=gate.onFailure{failures.add(it)}
        val store=androidx.lifecycle.ViewModelStore()
        try {
            val repo=CycleRepository(database)
            val vm=CycleCareViewModel(repo,prefs,SupabaseSyncService(database,prefs),SupabaseAuthService(prefs))
            store.put("vm",vm)
            gate.transitionMain { prefs.userId="synthetic-stageg-host-next" }.get(10,java.util.concurrent.TimeUnit.SECONDS)
            gate.awaitIdle().get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("main-immediate callbacks must not touch null backing fields: $failures",failures.none{it is NullPointerException})
            assertNotNull(vm.candidateSettingsEditor.value)
        } finally {store.clear();observer.close();database.close();Dispatchers.resetMain()}
    }
    @Test fun queuedOwnedIdentityResetAwaitsItsMutationAndListenerDrainThenReadsDefaults() = runBlocking {
        val name="stage-g-reset-${UUID.randomUUID()}"
        val backing=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        val prefs=AuthPreferences(context,backing,true)
        val owner="synthetic-reset-owner"
        prefs.userId=owner;prefs.isLocalOnlyMode=true;prefs.accessToken="synthetic-not-a-jwt"
        prefs.refreshToken="synthetic-not-a-refresh";prefs.sessionExpiresAtSeconds=123L
        val main=java.util.concurrent.ConcurrentLinkedQueue<()->Unit>()
        val gate=IdentityPublicationAdmission(main={main.add(it)},cannotBlock={false})
        prefs.attachPublicationAdmission(gate)
        var listenerSawDefaults=false
        gate.onTransition{listenerSawDefaults=prefs.userId==null&&!prefs.isLocalOnlyMode}
        val ahead=gate.transitionMain{Unit}
        val reset=gate.transitionIo {
            prefs.userId=owner
            prefs.isCloudSyncAllowed=false
            check(prefs.saveServerHealthConsentReceipt(owner,prefs.sessionGeneration,prefs.healthConsentGeneration,1L,false))
            prefs.accessToken=null;prefs.refreshToken=null;prefs.sessionExpiresAtSeconds=0L
            prefs.isLocalOnlyMode=false;prefs.userId=null
        }
        assertFalse("queued reset is not an acknowledged reset",reset.isDone)
        assertEquals(owner,prefs.userId)
        withTimeout(10_000){while(!reset.isDone){main.poll()?.invoke();delay(10)}}
        reset.get(1,java.util.concurrent.TimeUnit.SECONDS)
        val drained=gate.awaitIdle()
        withTimeout(10_000){while(!drained.isDone){main.poll()?.invoke();delay(10)}}
        ahead.join();drained.join()
        assertTrue("listener must observe completed defaults",listenerSawDefaults)
        val reloaded=AuthPreferences(context,context.getSharedPreferences(name,Context.MODE_PRIVATE),true)
        assertNull(reloaded.userId);assertNull(reloaded.accessToken);assertNull(reloaded.refreshToken)
        assertFalse(reloaded.isCloudSyncAllowed);assertFalse(reloaded.isLocalOnlyMode)
        assertEquals(0L,reloaded.sessionExpiresAtSeconds)
    }

    @Test fun realOwnerMoveClearsOldUiWhileStaleThenFreshRoutingPublishesOnlyNewOwner() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val a=db();val b=db();val prefs=prefs()
        val gate=IdentityPublicationAdmission(main={it()},cannotBlock={false})
        prefs.attachPublicationAdmission(gate)
        val store=androidx.lifecycle.ViewModelStore()
        suspend fun until(what:String,condition:()->Boolean){
            withTimeout(15_000){while(!condition()){
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(20)
            }}
            assertTrue(what,condition())
        }
        try {
            for((database,marker) in listOf(a to "OWNER_A",b to "OWNER_B")){
                database.runInTransaction {
                    val sql=database.openHelper.writableDatabase
                    RustDomainRows.write(sql,"settings",RustDomainRows.Row.Settings(com.cyclecare.app.data.local.CycleSettings(isOnboardingCompleted=true)))
                    RustDomainRows.write(sql,"2026-11-06",RustDomainRows.Row.Log(CycleDayLog("2026-11-06",notes=marker)))
                }
            }
            val repo=CycleRepository(a)
            val install=withContext(Dispatchers.IO){com.cyclecare.app.data.repository.RustPortInstaller(repo,RustBoundRoomHost(prefs),
                com.cyclecare.app.data.repository.RustPortInstallResolver.Resolution.bind(prefs,prefs.rustOperationSnapshot(),a)).install()}
            assertTrue(install is com.cyclecare.app.data.repository.RustPortInstaller.Outcome.Installed)
            val vm=CycleCareViewModel(repo,prefs,SupabaseSyncService(a,prefs),SupabaseAuthService(prefs))
            store.put("vm",vm)
            until("A loaded"){vm.logsMap.value.values.any{it.notes=="OWNER_A"}}
            val revision=repo.currentRustPortRevision()
            gate.transitionMain{prefs.userId="synthetic-owner-b"}.get(10,java.util.concurrent.TimeUnit.SECONDS)
            until("old owner hidden while stale"){vm.uiOwnerForTests=="synthetic-owner-b"&&vm.logsMap.value.isEmpty()}
            assertFalse(repo.installedRustPort()!!.isCurrent())
            assertTrue("refusal is visible while current routing is unresolved",vm.uiState.value.localReadFailed)
            val outcome=withContext(Dispatchers.IO){
                com.cyclecare.app.data.repository.RustPortTransitioner(context,prefs,repo){c,p->
                    com.cyclecare.app.data.repository.RustPortInstallResolver(c,p){_,owner->
                        check(owner=="synthetic-owner-b");b
                    }
                }.ensureCurrentRouting()
            }
            assertTrue(outcome is com.cyclecare.app.data.repository.RustPortTransitioner.Outcome.Replaced)
            until("B loaded after replacement"){vm.logsMap.value.values.any{it.notes=="OWNER_B"}&&!vm.uiState.value.localReadFailed}
            assertTrue(repo.currentRustPortRevision()>revision)
            assertTrue(repo.installedRustPort()!!.isCurrent())
            assertTrue(vm.logsMap.value.values.none{it.notes=="OWNER_A"})
        } finally {store.clear();a.close();b.close();Dispatchers.resetMain()}
    }

    @Test fun firstInstallSupersedingCapturedStartupMustRecomputeLocalAuthRoute() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val database=db();val prefs=prefs()
        prefs.attachPublicationAdmission(IdentityPublicationAdmission(main={it()},cannotBlock={false}))
        val store=androidx.lifecycle.ViewModelStore()
        val captured=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val calls=java.util.concurrent.atomic.AtomicInteger()
        CycleCareViewModel.beforeStartupPublicationForTests={
            if(calls.incrementAndGet()==1){captured.complete(Unit);release.await()}
        }
        try {
            database.runInTransaction {
                val sql=database.openHelper.writableDatabase
                RustDomainRows.write(sql,"settings",RustDomainRows.Row.Settings(com.cyclecare.app.data.local.CycleSettings(isOnboardingCompleted=true)))
                RustDomainRows.write(sql,"profile",RustDomainRows.Row.Profile(com.cyclecare.app.data.local.OnboardingProfile(isCompleted=true,accountMode="PRIVATE")))
            }
            val repo=CycleRepository(database)
            val vm=CycleCareViewModel(repo,prefs,SupabaseSyncService(database,prefs),SupabaseAuthService(prefs))
            store.put("vm",vm)
            withTimeout(10_000){captured.await()}
            assertFalse("startup decision deliberately held before publication",vm.uiState.value.hasCompletedInitialAuth)
            // This oracle owns held-startup supersession, not competing
            // observers' installer contention. Stop and JOIN those producers;
            // one installation must then succeed, with no Busy retry waiver.
            val heldStartup=vm.javaClass.getDeclaredField("startupJob").apply{isAccessible=true}.get(vm) as kotlinx.coroutines.Job
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.filter{it!==heldStartup}.toList().forEach{it.cancelAndJoin()}
            withContext(Dispatchers.IO){prefs.publicationAdmission!!.awaitIdle().get(10,java.util.concurrent.TimeUnit.SECONDS)}
            val outcome=withContext(Dispatchers.IO){
                com.cyclecare.app.data.repository.RustPortInstaller(repo,RustBoundRoomHost(prefs),
                    com.cyclecare.app.data.repository.RustPortInstallResolver.Resolution.bind(prefs,prefs.rustOperationSnapshot(),database)).install()
            }
            assertTrue("actual install outcome=$outcome",outcome is com.cyclecare.app.data.repository.RustPortInstaller.Outcome.Installed)
            withTimeout(10_000){while(vm.identityResetEpochForTests==0L){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20)}}
            release.complete(Unit)
            withTimeout(5000){while(!vm.uiState.value.hasCompletedInitialAuth){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20)}}
            assertTrue("local mode must not be routed to the sign-in screen",vm.uiState.value.hasCompletedInitialAuth)
            assertTrue(vm.uiState.value.settings.isOnboardingCompleted)
            assertNotNull(vm.uiState.value.onboardingProfile)
            assertFalse(vm.uiState.value.localReadFailed)
        }finally {
            release.complete(Unit);store.clear();CycleCareViewModel.beforeStartupPublicationForTests=null
            database.close();Dispatchers.resetMain()
        }
    }

    @Test fun androidQueuedAdmissionFirstInstallPublishesLocalAuthAndKeepsItAfterOnboardingWrite() = runBlocking { queuedHolderStartup(false) }
    @Test fun installedBeforeVmStartupPublishesAuthWithoutRevisionRefresh() = runBlocking { queuedHolderStartup(true) }
    @Test fun directRepositoryCompletionAfterStartupLeavesOnboardingRouteLatched() = runBlocking { queuedHolderStartup(true,true) }
    private suspend fun queuedHolderStartup(installBeforeVm:Boolean,assertLatched:Boolean=false) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val database=db();val prefs=prefs()
        com.cyclecare.app.security.StartupRouteDiagnostics.start()
        val cacheField=CycleCareDatabase::class.java.getDeclaredField("OWNER_INSTANCES").apply{isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        val cache=cacheField.get(null) as MutableMap<String,CycleCareDatabase>
        val namespace=CycleCareDatabase.namespaceFor(prefs.userId!!)
        check(!cache.containsKey(namespace));cache[namespace]=database
        com.cyclecare.app.data.repository.CycleCareComposition.resetForTests()
        com.cyclecare.app.data.repository.CycleCareComposition.authPreferencesFactoryForTests={prefs}
        val events=CopyOnWriteArrayList<String>()
        var vmRef:CycleCareViewModel?=null
        fun trace(event:String){
            val vm=vmRef
            events.add("${events.size}: $event prefs=${System.identityHashCode(prefs)} local=${prefs.isLocalOnlyMode} auth=${prefs.hasCompletedInitialAuth} session=${prefs.sessionGeneration} vm=${vm?.let{System.identityHashCode(it)}} reset=${vm?.identityResetEpochForTests} uiAuth=${vm?.uiState?.value?.hasCompletedInitialAuth}")
        }
        com.cyclecare.app.security.AdmissionStateFlow.beforeValuePaintForTests={old,next->
            if(old is com.cyclecare.app.ui.viewmodel.CycleUiState&&next is com.cyclecare.app.ui.viewmodel.CycleUiState){
                val frames=Throwable().stackTrace.filter{it.className.contains("CycleCareViewModel")}.take(4).joinToString("|")
                trace("paint auth=${old.hasCompletedInitialAuth}->${next.hasCompletedInitialAuth} done=${next.settings.isOnboardingCompleted} fail=${next.localReadFailed} caller=$frames")
            }
        }
        CycleCareViewModel.beforeStartupPublicationForTests={trace("startup-before")}
        CycleCareViewModel.afterStartupPublicationForTests={trace("startup-after")}
        CycleCareViewModel.afterRustRevisionObservationForTests={_,rev,invalid->trace("revision=$rev invalid=$invalid")}
        val store=androidx.lifecycle.ViewModelStore()
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        suspend fun until(condition:()->Boolean){withTimeout(15_000){while(!condition()){
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20)
        }}}
        try {
            val holder=com.cyclecare.app.data.repository.CycleCareComposition.current(context)
            val repo=holder.repository
            assertSame(prefs,holder.authPreferences)
            assertSame(database,CycleCareDatabase.getDatabase(context,prefs))
            fun launchInstall()=pool.submit<com.cyclecare.app.data.repository.RustPortInstaller.Outcome>{
                var last:com.cyclecare.app.data.repository.RustPortInstaller.Outcome=com.cyclecare.app.data.repository.RustPortInstaller.Outcome.Busy
                repeat(3){
                    if(last !is com.cyclecare.app.data.repository.RustPortInstaller.Outcome.Installed){
                        last=com.cyclecare.app.data.repository.RustPortInstaller(repo,RustBoundRoomHost(prefs),
                            com.cyclecare.app.data.repository.RustPortInstallResolver.Resolution.bind(prefs,prefs.rustOperationSnapshot(),database)).install()
                        if(last is com.cyclecare.app.data.repository.RustPortInstaller.Outcome.Busy)Thread.sleep(2000)
                    }
                }
                last
            }
            if(installBeforeVm){val install=launchInstall();until{install.isDone};assertTrue(install.get() is com.cyclecare.app.data.repository.RustPortInstaller.Outcome.Installed)}
            val factory=CycleCareViewModel.Factory(repo,holder.authPreferences,SupabaseSyncService(database,prefs),SupabaseAuthService(prefs))
            val vm=factory.create(CycleCareViewModel::class.java)
            vmRef=vm;trace("factory-created");store.put("vm",vm)
            vm.onForeground();trace("foreground")
            if(!installBeforeVm){val install=launchInstall();until{install.isDone};assertTrue("install outcome ${install.get()}",install.get() is com.cyclecare.app.data.repository.RustPortInstaller.Outcome.Installed)}
            until{vm.uiState.value.hasCompletedInitialAuth}
            assertTrue(prefs.isLocalOnlyMode)
            if(installBeforeVm)assertEquals("initial installed read must not rely on revision reset",0L,vm.identityResetEpochForTests)
            if(assertLatched)until{vm.uiState.value.showOnboarding}
            val complete=pool.submit{runBlocking{repo.completeOnboardingWithProfile(com.cyclecare.app.data.local.OnboardingProfile(accountMode="PRIVATE"))}}
            until{complete.isDone};complete.get()
            until{vm.uiState.value.settings.isOnboardingCompleted}
            assertTrue("local completed route must survive real queued updates",vm.uiState.value.hasCompletedInitialAuth)
            assertFalse(vm.uiState.value.localReadFailed)
            repeat(100){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20)}
            assertTrue(vm.uiState.value.hasCompletedInitialAuth)
            if(assertLatched){
                assertTrue(vm.uiState.value.settings.isOnboardingCompleted)
                assertNotNull(vm.uiState.value.onboardingProfile)
                assertTrue("direct repository write is not ViewModel completion intent",vm.uiState.value.showOnboarding)
                println("STAGEG_LATCH auth=${vm.uiState.value.hasCompletedInitialAuth} completed=${vm.uiState.value.settings.isOnboardingCompleted} profile=${vm.uiState.value.onboardingProfile!=null} showOnboarding=${vm.uiState.value.showOnboarding}")
                val completionPainted=java.util.concurrent.atomic.AtomicBoolean(false)
                CycleCareViewModel.afterCompletionPublicationForTests={same->if(same===vm)completionPainted.set(true)}
                vm.completeOnboarding(goals=emptyList(),lastPeriodDate=java.time.LocalDate.now().minusDays(10),cycleLength=28,cycleLengthOption="28",periodDuration=5,pin="",enableLock=false)
                until{
                    val receipt=vm.uiReadForTests("finish")?.record
                    val finalization=vm.onboardingStatusFinalization
                    completionPainted.get()&&receipt?.effectCompleted==true&&receipt.status==CycleCareViewModel.UiReadStatus.PAINTED&&
                        receipt.waitEnd==CycleCareViewModel.UiWaitEnd.RETURNED&&finalization?.isDone==true&&!finalization.isCompletedExceptionally&&
                        !vm.uiState.value.showOnboarding&&!vm.uiState.value.isCompletingOnboarding
                }
                assertNull(vm.onboardingStatusFinalizationFailure)
                assertNull(vm.uiState.value.onboardingCompletionMessage)
                assertTrue(vm.uiState.value.onboardingProfile?.isCompleted==true)
                // The queued admission's final read also needs the fixture's
                // main-looper pump, just like startup/completion above. Awaiting
                // withContext directly parks this Robolectric main thread.
                val stored=coroutineScope{
                    val storedRead=async(Dispatchers.IO){repo.getStoredSnapshot()}
                    until{storedRead.isCompleted}
                    storedRead.await()
                }
                assertTrue(stored.settings.isOnboardingCompleted&&stored.profile?.isCompleted==true)
                repeat(100){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20)}
                assertFalse(vm.uiState.value.showOnboarding)
                assertTrue(vm.uiState.value.settings.isOnboardingCompleted)
                assertTrue(vm.uiState.value.hasCompletedInitialAuth)
                println("STAGEG_REAL_VM_COMPLETION auth=true completed=true showOnboarding=false")
            }
            trace("stable-finish revision=${repo.currentRustPortRevision()}")
            val ring=com.cyclecare.app.security.StartupRouteDiagnostics.dumpOnce()
            assertTrue(ring.any{it.contains("START_CHECK")})
            assertTrue(ring.any{it.contains("UI_AFTER_ASSIGN")})
            if(installBeforeVm)assertTrue("selected startup receipt must be captured",ring.any{it.contains("READ_RECEIPT")})
            ring.forEach{println("STAGEG_RING $it")}
            assertTrue(com.cyclecare.app.security.StartupRouteDiagnostics.dumpOnce().isEmpty())
        }finally{
            events.forEach{println("STAGEG_TRACE $it")}
            com.cyclecare.app.security.StartupRouteDiagnostics.stop()
            com.cyclecare.app.security.AdmissionStateFlow.beforeValuePaintForTests=null
            CycleCareViewModel.beforeStartupPublicationForTests=null
            CycleCareViewModel.afterStartupPublicationForTests=null
            CycleCareViewModel.afterCompletionPublicationForTests=null
            CycleCareViewModel.afterRustRevisionObservationForTests=null
            store.clear();pool.shutdown();check(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))
            val idle=prefs.publicationAdmission!!.awaitIdle();until{idle.isDone};idle.get()
            check(com.cyclecare.app.data.repository.CycleCareComposition.resetForTestsAndAwait())
            check(cache.remove(namespace)===database);database.close();Dispatchers.resetMain()
        }
    }

    /** Actual local-only foreground writer, queued behind startup paint with
     * unchanged owner/session. This is a discriminating overwrite witness,
     * not proof that the device failure took this schedule. */
    @Test fun queuedForegroundLocalWalletUpdateMustPreserveStartupAuth() = runBlocking { foregroundOverwrite(false) }
    @Test fun queuedForegroundDoesNotOverwriteAlreadyInstalledStartupInObservedSchedule() = runBlocking { foregroundOverwrite(true) }
    private suspend fun foregroundOverwrite(preinstalled:Boolean) {
        Dispatchers.setMain(android.os.Handler(android.os.Looper.getMainLooper()).asCoroutineDispatcher().immediate)
        val database=db();val prefs=prefs()
        val gate=IdentityPublicationAdmission.forAndroid();prefs.attachPublicationAdmission(gate)
        val store=androidx.lifecycle.ViewModelStore()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val startupReached=CompletableDeferred<Unit>()
        val startupQueued=java.util.concurrent.atomic.AtomicBoolean(false)
        gate.afterEnqueueForTests={kind->
            if(startupReached.isCompleted&&entered.count==0L&&
                Throwable().stackTrace.any{it.className.contains("CycleCareViewModel")&&it.toString().contains("runStartup")}){
                if(kind==if(preinstalled)"publication" else "transition")startupQueued.set(true)
            }
        }
        val startupPublished=CompletableDeferred<Unit>()
        CycleCareViewModel.afterStartupPublicationForTests={startupPublished.complete(Unit)}
        val events=CopyOnWriteArrayList<String>()
        var blocker:java.util.concurrent.CompletableFuture<Unit>?=null
        CycleCareViewModel.beforeStartupPublicationForTests={vm->
            if(!preinstalled){
                // Accepted writer oracle: isolate the actual startup job and
                // real foreground writer, not independent Room/network flows.
                // Concurrent observer rejection is a separate control below.
                val startup=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                val scopeJob=vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]
                scopeJob!!.children.filter{it!==startup}.toList().forEach{it.cancelAndJoin()}
                val idle=gate.awaitIdle()
                while(!idle.isDone)delay(10)
                idle.get()
            }
            blocker=gate.exclusiveIo{entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
            startupReached.complete(Unit)
        }
        com.cyclecare.app.security.AdmissionStateFlow.beforeValuePaintForTests={old,next->
            if(old is com.cyclecare.app.ui.viewmodel.CycleUiState&&next is com.cyclecare.app.ui.viewmodel.CycleUiState)
                events.add("paint ${old.hasCompletedInitialAuth}->${next.hasCompletedInitialAuth} prefsAuth=${prefs.hasCompletedInitialAuth} caller="+Throwable().stackTrace.filter{it.className.contains("CycleCareViewModel")}.take(3).joinToString("|"))
        }
        suspend fun until(condition:()->Boolean){withTimeout(10_000){while(!condition()){
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20)
        }}}
        try {
            val repo=CycleRepository(database)
            if(preinstalled){
                val install=CoroutineScope(Dispatchers.IO).async{
                    com.cyclecare.app.data.repository.RustPortInstaller(repo,RustBoundRoomHost(prefs),
                        com.cyclecare.app.data.repository.RustPortInstallResolver.Resolution.bind(prefs,prefs.rustOperationSnapshot(),database)).install()
                }
                until{install.isCompleted};assertTrue(install.await() is com.cyclecare.app.data.repository.RustPortInstaller.Outcome.Installed)
            }
            val vm=CycleCareViewModel(repo,prefs,SupabaseSyncService(database,prefs),SupabaseAuthService(prefs));store.put("vm",vm)
            until{startupReached.isCompleted&&entered.count==0L}
            // Observe the actual startup enqueue, not an unrelated queued
            // calculation/read. The occupied IO prevents its paint yet.
            until{startupQueued.get()}
            assertFalse(vm.uiState.value.hasCompletedInitialAuth)
            vm.onForeground()
            release.countDown()
            until{startupPublished.isCompleted}
            val idle=gate.awaitIdle();until{idle.isDone};idle.get()
            repeat(100){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20)}
            println("STAGEG_OVERWRITE preinstalled=$preinstalled revision=${repo.currentRustPortRevision()} reset=${vm.identityResetEpochForTests} prefsLocal=${prefs.isLocalOnlyMode} prefsAuth=${prefs.hasCompletedInitialAuth} session=${prefs.sessionGeneration} finalUiAuth=${vm.uiState.value.hasCompletedInitialAuth}")
            events.forEach{println("STAGEG_OVERWRITE $it")}
            assertTrue(prefs.hasCompletedInitialAuth)
            assertTrue("foreground wallet refresh must preserve admitted startup auth",vm.uiState.value.hasCompletedInitialAuth)
            assertTrue("no late aggregate may undo startup auth",events.none{it.startsWith("paint true->false")})
            if(!preinstalled){
                val startup=events.indexOfFirst{it.startsWith("paint false->true")&&it.contains("runStartup")}
                val foreground=events.indexOfFirst{it.startsWith("paint true->true")&&it.contains("refreshWalletNow")}
                assertTrue("actual foreground writer must paint after startup: $events",startup>=0&&foreground>startup)
            }
        } finally {
            release.countDown();store.clear()
            blocker?.get(10,java.util.concurrent.TimeUnit.SECONDS)
            gate.afterEnqueueForTests=null
            val idle=gate.awaitIdle();until{idle.isDone};idle.get()
            CycleCareViewModel.beforeStartupPublicationForTests=null
            CycleCareViewModel.afterStartupPublicationForTests=null
            com.cyclecare.app.security.AdmissionStateFlow.beforeValuePaintForTests=null
            database.close();Dispatchers.resetMain()
        }
    }

    @Test fun rejectedForegroundStampHasNoEffectAndKeepsStartupAuth() = runBlocking {
        Dispatchers.setMain(android.os.Handler(android.os.Looper.getMainLooper()).asCoroutineDispatcher().immediate)
        val database=db();val prefs=prefs();val gate=IdentityPublicationAdmission.forAndroid();prefs.attachPublicationAdmission(gate)
        val store=androidx.lifecycle.ViewModelStore()
        val reads=java.util.concurrent.atomic.AtomicInteger()
        val rejected=java.util.concurrent.atomic.AtomicBoolean()
        com.cyclecare.app.security.StartupRouteDiagnostics.start()
        suspend fun until(condition:()->Boolean){withTimeout(10_000){while(!condition()){
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();delay(20)
        }}}
        try{
            val vm=CycleCareViewModel(CycleRepository(database),prefs,SupabaseSyncService(database,prefs),SupabaseAuthService(prefs));store.put("vm",vm)
            until{vm.uiState.value.hasCompletedInitialAuth}
            // During actual wallet stamp completion, enqueue an unrelated
            // transition before its publication. No identity or auth forcing.
            gate.afterEnqueueForTests={kind->
                if(kind=="read"&&Throwable().stackTrace.any{it.toString().contains("refreshWalletNow")}){
                    reads.incrementAndGet()
                }
            }
            com.cyclecare.app.security.AdmissionStateFlow.beforeValuePaintForTests={_,next->
                if(next is com.cyclecare.app.ui.viewmodel.CycleUiState&&Throwable().stackTrace.any{it.toString().contains("refreshWalletNow")})rejected.set(true)
            }
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            val held=gate.exclusiveIo{entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
            until{entered.count==0L};vm.refreshWalletNow();gate.transition{Unit};gate.afterEnqueueForTests=null;release.countDown()
            until{held.isDone};val idle=gate.awaitIdle();until{idle.isDone};idle.get()
            assertEquals(1,reads.get());assertFalse("rejected wallet publication must not assign",rejected.get())
            assertTrue(vm.uiState.value.hasCompletedInitialAuth)
            val ring=com.cyclecare.app.security.StartupRouteDiagnostics.dumpOnce()
            assertTrue("must record a rejected stamp receipt: $ring",ring.any{it.contains("STAMP_RECEIPT")&&it.endsWith(",0")})
            ring.forEach{println("STAGEG_REJECTED $it")}
        }finally{
            gate.afterEnqueueForTests=null;store.clear();com.cyclecare.app.security.StartupRouteDiagnostics.stop()
            com.cyclecare.app.security.AdmissionStateFlow.beforeValuePaintForTests=null
            val idle=gate.awaitIdle();until{idle.isDone};idle.get();database.close();Dispatchers.resetMain()
        }
    }

    @Test fun startupDiagnosticRingIsBoundedAndDumpsOnlyOnce(){
        val trace=com.cyclecare.app.security.StartupRouteDiagnostics
        trace.start();try{
            repeat(300){trace.record(com.cyclecare.app.security.StartupRouteDiagnostics.Event.START_READ,this,it.toLong())}
            val rows=trace.dumpOnce();assertEquals(128,rows.size);assertTrue(rows.first().startsWith("173 "))
            assertTrue(trace.dumpOnce().isEmpty())
        }finally{trace.stop()}
    }

}
