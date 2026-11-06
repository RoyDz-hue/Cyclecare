package com.cyclecare.app.data.local

import android.content.Context

/** Durable lifecycle gate: a wipe cannot be undone by a later pull or stale worker. */
object OwnerDataGate {
    private val admissions=java.util.WeakHashMap<android.content.SharedPreferences,com.cyclecare.app.security.IdentityPublicationAdmission>()
    internal fun attachPublicationAdmission(context:Context,admission:com.cyclecare.app.security.IdentityPublicationAdmission){synchronized(admissions){admissions[store(context)]=admission}}
    private const val PREFS = "cyclecare_owner_data_lifecycle_v1"
    private fun key(identity: String?): String =
        "wiped_" + (identity?.takeIf { it.isNotBlank() }?.let(CycleCareDatabase::namespaceFor) ?: "legacy_guest")

    private fun store(context:Context)=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    data class Snapshot(val wiped:Boolean,val generation:Long)
    fun snapshot(context:Context,identity:String?):Snapshot {
        val prefs=store(context);return synchronized(prefs){Snapshot(prefs.getBoolean(key(identity),false),prefs.getLong("generation_"+key(identity),0L))}
    }
    /** Order for integrated writers: auth prefs -> lifecycle prefs -> host -> DB.
     * Never suspend or call out from block. Existing reads remain compatible.
     */
    fun <T> runIfUnchanged(context:Context,identity:String?,expected:Snapshot,block:()->T):T? {
        val prefs=store(context);return synchronized(prefs){if(snapshot(context,identity)==expected)block()else null}
    }
    fun isWiped(context:Context,identity:String?)=snapshot(context,identity).wiped
    fun markWiped(context:Context,identity:String?)=set(context,identity,true)
    /** Only an explicit user action may resume a wiped namespace. */
    fun resumeAfterExplicitUserAction(context:Context,identity:String?)=set(context,identity,false)
    private fun set(context:Context,identity:String?,wiped:Boolean){
        val admission=synchronized(admissions){admissions[store(context)]}
        if(admission==null)setAdmitted(context,identity,wiped)else admission.mutate{setAdmitted(context,identity,wiped)}
    }
    private fun setAdmitted(context:Context,identity:String?,wiped:Boolean){
        val prefs=store(context);synchronized(prefs){
            val generation=Math.addExact(prefs.getLong("generation_"+key(identity),0L),1L)
            check(prefs.edit().putBoolean(key(identity),wiped).putLong("generation_"+key(identity),generation).commit())
        }
    }
}
