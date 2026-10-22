package com.cyclecare.app.data.economy

import com.cyclecare.app.data.supabase.SupabaseConfig
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Foreground-only invalidation. Events trigger an authenticated read, never trust payload balances. */
class WalletRealtimeObserver(private val auth: AuthPreferences, private val scope: CoroutineScope, private val changed: () -> Unit) {
    private val client=OkHttpClient.Builder().pingInterval(25,TimeUnit.SECONDS).build()
    private var job: Job?=null
    private var socket: WebSocket?=null
    fun start() {
        stop()
        if(auth.userId==null || auth.isLocalOnlyMode)return
        val owner=auth.effectiveUserId;val session=auth.sessionGeneration
        job=scope.launch {
            while(isActive && owner==auth.effectiveUserId && session==auth.sessionGeneration) {
                val token=SupabaseSessionManager.validAccessToken(auth) ?: break
                val closed=CompletableDeferred<Unit>()
                val topic="realtime:wallet:$owner"
                val request=Request.Builder().url(SupabaseConfig.url.replaceFirst("https://","wss://")+"/realtime/v1/websocket?apikey="+SupabaseConfig.anonKey+"&vsn=1.0.0").build()
                socket=client.newWebSocket(request,object: WebSocketListener(){
                    override fun onOpen(ws: WebSocket,response: Response) {
                        val config=JSONObject().put("postgres_changes",org.json.JSONArray().put(JSONObject().put("event","*").put("schema","public").put("table","economy_profiles").put("filter","user_id=eq.$owner")))
                        ws.send(JSONObject().put("topic",topic).put("event","phx_join").put("payload",JSONObject().put("config",config).put("access_token",token)).put("ref","1").toString())
                    }
                    override fun onMessage(ws: WebSocket,text: String) {
                        val event=runCatching{JSONObject(text).optString("event")}.getOrDefault("")
                        if(event=="postgres_changes" && owner==auth.effectiveUserId && session==auth.sessionGeneration)scope.launch{changed()}
                    }
                    override fun onClosed(ws:WebSocket,code:Int,reason:String){closed.complete(Unit)}
                    override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){closed.complete(Unit)}
                })
                try {
                    withTimeoutOrNull(180_000) { while(!closed.isCompleted && isActive){delay(25_000);socket?.send("{\"topic\":\"phoenix\",\"event\":\"heartbeat\",\"payload\":{},\"ref\":\"heartbeat\"}")} }
                } finally {socket?.cancel();socket=null}
                if(isActive){changed();delay(10_000)}
            }
        }
    }
    fun stop(){job?.cancel();job=null;socket?.cancel();socket=null}
}
