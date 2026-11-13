package com.cyclecare.app.data.economy

import com.cyclecare.app.data.supabase.SupabaseConfig
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Foreground-only invalidation. Events trigger an authenticated read, never trust payload balances. */
class WalletRealtimeObserver(private val auth: AuthPreferences, private val scope: CoroutineScope,
    private val socketFactory:WebSocket.Factory=OkHttpClient.Builder().pingInterval(25,TimeUnit.SECONDS).build(),private val changed: () -> Unit) {
    private var job: Job?=null
    private var socket: WebSocket?=null
    @Volatile private var generation=0L
    private val connectionEpoch=java.util.concurrent.atomic.AtomicLong()
    fun start() {
        stop()
        if(auth.userId==null || auth.isLocalOnlyMode)return
        val owner=auth.effectiveUserId;val session=auth.sessionGeneration
        val identity=auth.rustOperationSnapshot()
        val lifecycle=com.cyclecare.app.data.local.OwnerDataGate.snapshot(auth.context,identity.operation.owner)
        val capturedGeneration=generation
        val current={capturedGeneration==generation&&owner==auth.effectiveUserId&&session==auth.sessionGeneration&&
            (auth.publicationAdmission==null||(auth.rustOperationSnapshot()==identity&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(auth.context,identity.operation.owner)==lifecycle))}
        fun notifyChange(){if(current())scope.launch(com.cyclecare.app.security.PublicationOrigin.Context(current)){if(current())changed()}}
        job=scope.launch {
            while(isActive && current()) {
                val token=SupabaseSessionManager.validAccessToken(auth) ?: break
                if(!current())break
                val closed=CompletableDeferred<Unit>()
                val topic="realtime:wallet:$owner"
                val request=Request.Builder().url(SupabaseConfig.url.replaceFirst("https://","wss://")+"/realtime/v1/websocket?apikey="+SupabaseConfig.anonKey+"&vsn=1.0.0").build()
                val connection=connectionEpoch.incrementAndGet()
                val socketCurrent={current()&&connectionEpoch.get()==connection}
                val opened=socketFactory.newWebSocket(request,object: WebSocketListener(){
                    override fun onOpen(ws: WebSocket,response: Response) {
                        if(!socketCurrent()){ws.cancel();return}
                        val config=JSONObject().put("postgres_changes",org.json.JSONArray().put(JSONObject().put("event","*").put("schema","public").put("table","economy_profiles").put("filter","user_id=eq.$owner")))
                        ws.send(JSONObject().put("topic",topic).put("event","phx_join").put("payload",JSONObject().put("config",config).put("access_token",token)).put("ref","1").toString())
                    }
                    override fun onMessage(ws: WebSocket,text: String) {
                        val event=runCatching{JSONObject(text).optString("event")}.getOrDefault("")
                        if(event=="postgres_changes"&&socketCurrent())scope.launch(com.cyclecare.app.security.PublicationOrigin.Context(socketCurrent)){if(socketCurrent())changed()}
                    }
                    override fun onClosed(ws:WebSocket,code:Int,reason:String){closed.complete(Unit)}
                    override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){closed.complete(Unit)}
                })
                socket=opened
                try {
                    withTimeoutOrNull(180_000) { while(!closed.isCompleted && isActive){delay(25_000);opened.send("{\"topic\":\"phoenix\",\"event\":\"heartbeat\",\"payload\":{},\"ref\":\"heartbeat\"}")} }
                } finally {opened.cancel();if(socket===opened)socket=null}
                if(isActive&&current()){notifyChange();delay(10_000)}
            }
        }
    }
    fun stop(){generation=Math.addExact(generation,1L);connectionEpoch.incrementAndGet();job?.cancel();job=null;socket?.cancel();socket=null}
}
