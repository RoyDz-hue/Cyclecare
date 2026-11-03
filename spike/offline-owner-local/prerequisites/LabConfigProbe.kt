import com.cyclecare.app.lab.store.*
fun main(){
 var n=0
 val c=SyntheticFixtureConfig.create("a".repeat(32),"b".repeat(64));c.validate("com.cyclecare.app.lab");n++
 for(id in listOf("com.cyclecare.app","com.cyclecare.app.lab.test","","other")){check(runCatching{c.validate(id)}.isFailure);n++}
 for(x in listOf(c.copy(filename="cycle_care_database"),c.copy(alias="cyclecare_room_db_wrapping_key_v1"),c.copy(prefs="cyclecare_local_db_key"),c.copy(filename="../x"),c.copy(runId=""),c.copy(ownerDigest=""),c.copy(alias=""),c.copy(prefs=""),c.copy(filename=""))){check(runCatching{x.validate("com.cyclecare.app.lab")}.isFailure);n++}
 println("PASS $n pure config assertions; no Android Context/key/database execution")
}
