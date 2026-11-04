package com.cyclecare.app.lab.store
import java.nio.file.Files
import java.io.File
fun main(){
 val base=Files.createTempDirectory("fixture-containment");val real=Files.createDirectory(base.resolve("real"));val outside=Files.createDirectory(base.resolve("outside"));val alias=base.resolve("alias");Files.createSymbolicLink(alias,real)
 val name="cyclecare_lab_synthetic_"+"a".repeat(32)+"_"+"b".repeat(64)+".db";var n=0
 fun resolve(dir:File,f:String)=LabFixturePath.resolve(dir,f){Files.isSymbolicLink(it.toPath())}
 fun verify(ok:Boolean){check(ok);n++}
 verify(resolve(alias.toFile(),name)==real.resolve(name).toFile());verify(resolve(real.toFile(),name)==real.resolve(name).toFile())
 Files.write(real.resolve(name),byteArrayOf(1));verify(resolve(alias.toFile(),name)==real.resolve(name).toFile());Files.delete(real.resolve(name))
 for(target in listOf(outside.resolve("escape"),real.resolve("contained"),outside.resolve("nonexistent"))){Files.createSymbolicLink(real.resolve(name),target);verify(runCatching{resolve(alias.toFile(),name)}.isFailure);Files.delete(real.resolve(name))}
 for(bad in listOf("../"+name,"/"+name,name+"/x",name+"-wal","cycle_care_database","",name.replace(".db",".db\u0000"))){verify(runCatching{resolve(alias.toFile(),bad)}.isFailure)}
 verify(runCatching{LabFixturePath.resolve(alias.toFile(),name){throw SecurityException("deny")}}.isFailure)
 Files.delete(alias);Files.delete(real);Files.delete(outside);Files.delete(base)
 println("PASS $n local canonical containment assertions: ancestor alias accepted, final contained/escape/dangling symlinks and hostile filenames rejected; no Android effects")
}
