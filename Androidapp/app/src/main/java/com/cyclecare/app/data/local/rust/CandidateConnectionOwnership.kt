package com.cyclecare.app.data.local.rust

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Inert connection boundary. NOT selected by the production provider.
 * One-way claim refuses rather than waits when legacy cursors/transactions are
 * live. Every retained connection/statement checks authority at EXECUTION, not
 * creation. A thread-local grant is synchronous only and must never cross suspend.
 * No keys, paths, owners, migrations or preferences are read here. */
internal class CandidateConnectionOwnership {
 class Rejected(reason:String):IllegalStateException(reason)
 class Permit private constructor(){companion object{internal fun issue()=Permit()}}
 private val lock=Any()
 private var owner:Permit?=null
 private var transactions=0
 private var cursors=0
 private val granted=ThreadLocal<Permit?>()
 private fun checkAccess(){if(owner!=null&&granted.get()!==owner)throw Rejected("candidate_connection_exclusive")}
 fun claim():Permit=synchronized(lock){
  if(owner!=null)throw Rejected("candidate_connection_already_claimed")
  if(transactions!=0||cursors!=0)throw Rejected("candidate_connection_busy")
  Permit.issue().also{owner=it}
 }
 fun <T> access(permit:Permit,action:()->T):T{
  synchronized(lock){if(owner!==permit)throw Rejected("candidate_connection_wrong_permit")}
  val previous=granted.get();granted.set(permit)
  return try{action()}finally{granted.set(previous)}
 }
 fun wrap(factory:SupportSQLiteOpenHelper.Factory)=SupportSQLiteOpenHelper.Factory{config->
  wrapHelper(factory.create(config))
 }
 private fun invoke(target:Any,method:java.lang.reflect.Method,args:Array<out Any?>?):Any?=try{
  method.invoke(target,*(args?:emptyArray()))
 }catch(e:InvocationTargetException){throw e.targetException}
 private fun wrapHelper(helper:SupportSQLiteOpenHelper):SupportSQLiteOpenHelper =
  Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SupportSQLiteOpenHelper::class.java)){_,method,args->
   synchronized(lock){
    checkAccess()
    val result=invoke(helper,method,args)
    if(result is SupportSQLiteDatabase)wrapDatabase(result)else result
   }
  } as SupportSQLiteOpenHelper
 private fun wrapDatabase(db:SupportSQLiteDatabase):SupportSQLiteDatabase =
  Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SupportSQLiteDatabase::class.java)){_,method,args->
   synchronized(lock){
    checkAccess()
    val result=invoke(db,method,args)
    when {
     method.name.startsWith("beginTransaction")->{transactions++;result}
     method.name=="endTransaction"->{check(transactions>0){"untracked_transaction"};transactions--;result}
     result is Cursor->{cursors++;wrapCursor(result,granted.get())}
     result is SupportSQLiteStatement->wrapStatement(result)
     else->result
    }
   }
  } as SupportSQLiteDatabase
 private fun wrapStatement(statement:SupportSQLiteStatement):SupportSQLiteStatement =
  Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SupportSQLiteStatement::class.java)){_,method,args->
   synchronized(lock){checkAccess();invoke(statement,method,args)}
  } as SupportSQLiteStatement
 private fun wrapCursor(cursor:Cursor,permit:Permit?):Cursor {
  var closedHere=false
  return Proxy.newProxyInstance(javaClass.classLoader,arrayOf(Cursor::class.java)){_,method,args->
   synchronized(lock){
    if(method.name=="close"){
     if(!closedHere){invoke(cursor,method,args);closedHere=true;cursors--}
     null
    }else{
     if(owner!=null&&(permit!==owner||granted.get()!==owner))throw Rejected("candidate_cursor_outside_access")
     invoke(cursor,method,args)
    }
   }
  } as Cursor
 }
}
