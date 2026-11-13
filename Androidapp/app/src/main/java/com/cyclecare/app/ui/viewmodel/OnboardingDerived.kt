package com.cyclecare.app.ui.viewmodel
import org.json.JSONArray
import org.json.JSONObject
import com.cyclecare.app.rust.CycleCareCoreBridge as B
/** Rust owns short-circuit evaluation, arithmetic and copy; only named JVM primitives can be requested. */
internal object OnboardingDerived {
    private val keys=mapOf("privacy" to setOf("age","terms"),"answered" to setOf("exact","chipPresent"),"estimate" to setOf("exact","chipPresent","unknown"),"choice" to setOf("exact","estimate","chip"),"days" to setOf("span"),"day" to setOf("estimate","days","cycle"),"active" to setOf("estimate","ongoing","day","duration"),"phase" to setOf("estimate","active","day1","cycle","day2"),"offset" to setOf("chip"),"endOffset" to setOf("duration"),"any" to setOf("flags"),"confirmed" to setOf("ongoing","confirmed","exact","end","ordered"))
    /** Complete independent expression evaluator, also the original whole-call fallback. */
    internal fun oracle(op:String,read:(String)->Any?):Any {
        fun bool(k:String)=read(k) as Boolean
        fun int(k:String)=read(k) as Int
        return when(op){
            "privacy"->bool("age")&&bool("terms")
            "answered"->bool("exact")||bool("chipPresent")
            "estimate"->bool("exact")||(bool("chipPresent")&&!bool("unknown"))
            "choice"->when{bool("exact")->"EXACT";!bool("estimate")->"UNKNOWN";else->"APPROXIMATE:${read("chip") as String}"}
            "days"->(read("span") as Long).toInt().coerceAtLeast(0)
            "day"->if(bool("estimate"))(int("days")%int("cycle"))+1 else 0
            "active"->bool("estimate")&&(bool("ongoing")||(int("day")<=int("duration")))
            "phase"->when{!bool("estimate")->"No estimate yet";bool("active")->"Menstrual Phase";int("day1")<(int("cycle")-14)-4->"Follicular Phase";int("day2") in ((int("cycle")-14)-4)..((int("cycle")-14)+1)->"Estimated fertile window";else->"Luteal Phase"}
            "offset"->when(read("chip") as String?){"Today"->0;"A few days ago"->4;"About a week ago"->7;"About two weeks ago"->14;"It varies / I don’t know", "I don’t know"->0;else->11}
            "endOffset"->(int("duration")-1).coerceAtLeast(0)
            "any"->(read("flags") as List<*>).any{it as Boolean}
            "confirmed"->!bool("ongoing")&&bool("confirmed")&&bool("exact")&&bool("end")&&bool("ordered")
            else->error("unknown derived op")
        }
    }
    internal fun evaluate(op:String,read:(String)->Any?):Any {
        val allowed=keys.getValue(op);val captured=linkedMapOf<String,Any?>();val evidence=JSONObject()
        fun get(key:String):Any? {require(key in allowed);if(captured.containsKey(key))return captured[key];return read(key).also{captured[key]=it}}
        repeat(allowed.size+1){
            val reply=B.call("onboarding_derived_full_v1",JSONObject().put("op",op).put("evidence",evidence))
            val decoded=decode(reply,op)
            when(decoded){
                is Result.Value->return decoded.value
                is Result.Need->{if(captured.containsKey(decoded.key))return oracle(op,::get);val value=get(decoded.key);if(value is String&&!safe(value))return oracle(op,::get);evidence.put(decoded.key,if(value is List<*>)JSONArray(value)else value ?: JSONObject.NULL)}
                Result.Arithmetic->{return oracle(op,::get)}
                null->return oracle(op,::get)
            }
        }
        return oracle(op,::get)
    }
    private fun safe(s:String):Boolean {var i=0;while(i<s.length){val c=s[i++];if(Character.isHighSurrogate(c)){if(i==s.length||!Character.isLowSurrogate(s[i++]))return false}else if(Character.isLowSurrogate(c))return false};return true}
    internal sealed interface Result {data class Need(val key:String):Result;data class Value(val value:Any):Result;data object Arithmetic:Result}
    internal fun decode(reply:JSONObject?,op:String):Result? = runCatching {
        if(reply==null)return null;require(reply.keys().asSequence().toSet()==setOf("ok"));val ok=reply.opt("ok") as? JSONObject ?: return null;require(ok.opt("version") is Int&&ok.opt("version")==1)
        when(ok.keys().asSequence().toSet()){
            setOf("version","need")->{val key=ok.opt("need") as? String ?: return null;require(key in keys.getValue(op));Result.Need(key)}
            setOf("version","throw")->{require(op=="day"&&ok.opt("throw")=="arithmetic");Result.Arithmetic}
            setOf("version","value")->{val v=ok.opt("value");when(op){"choice","phase"->{require(v is String&&safe(v));Result.Value(v)};"days","day","offset","endOffset"->{require(v is Int);Result.Value(v)};else->{require(v is Boolean);Result.Value(v)}}}
            else->null
        }
    }.getOrNull()
    fun bool(op:String,s:OnboardingUiState):Boolean=evaluate(op){key->when(key){"age"->s.isAgeVerified;"terms"->s.isTermsAgreed;"exact"->(s.selectedExactDate!=null);"chipPresent"->(s.selectedApproximateChip!=null);"unknown"->s.selectedApproximateChip.let{it.isNullOrBlank()||it.equals("I don’t know",true)||it.equals("It varies / I don’t know",true)};"estimate"->s.hasCycleStartEstimate;"ongoing"->s.isPeriodOngoing;"day"->s.dayOfCycle;"duration"->s.periodDurationDays;else->error("unexpected bool primitive")}} as Boolean
    fun choice(s:OnboardingUiState):String=evaluate("choice"){when(it){"exact"->(s.selectedExactDate!=null);"estimate"->s.hasCycleStartEstimate;"chip"->s.selectedApproximateChip.orEmpty();else->error("choice primitive")}} as String
    fun days(span:()->Long):Int=evaluate("days"){span()} as Int
    fun day(s:OnboardingUiState):Int=evaluate("day"){when(it){"estimate"->s.hasCycleStartEstimate;"days"->s.daysSinceLastPeriod;"cycle"->s.cycleLengthDays;else->error("day primitive")}} as Int
    fun phase(s:OnboardingUiState):String=evaluate("phase"){when(it){"estimate"->s.hasCycleStartEstimate;"active"->s.isPeriodActiveToday;"day1","day2"->s.dayOfCycle;"cycle"->s.cycleLengthDays;else->error("phase primitive")}} as String
    fun offset(chip:String?):Int=evaluate("offset"){chip} as Int
    fun endOffset(duration:Int):Int=evaluate("endOffset"){duration} as Int
    fun any(flags:()->List<Boolean>):Boolean=evaluate("any"){flags()} as Boolean
    fun confirmed(s:OnboardingUiState):java.time.LocalDate? {val keep=evaluate("confirmed"){when(it){"ongoing"->s.isPeriodOngoing;"confirmed"->s.periodEndDateConfirmed;"exact"->(s.selectedExactDate!=null);"end"->(s.selectedPeriodEndDate!=null);"ordered"->com.cyclecare.app.data.repository.CycleRepository.validatePeriodDates(s.selectedExactDate,s.selectedPeriodEndDate);else->error("confirmed primitive")}} as Boolean;return if(keep)s.selectedPeriodEndDate else null}
}
