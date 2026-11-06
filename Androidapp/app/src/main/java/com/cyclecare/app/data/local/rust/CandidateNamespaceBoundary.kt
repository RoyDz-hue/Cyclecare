package com.cyclecare.app.data.local.rust

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Isolated factory-local experiment, NOT a namespace adoption certificate.
 * No monitor crosses SQLite invocation. Claim fails while calls/cursors/tx live.
 * Connection getters are not authority: retained handles check each execution.
 * Room maintenance can read/reset only its invalidation log, not health rows.
 * This cannot protect a second helper/connection not built through this factory.
 */
internal class CandidateNamespaceBoundary {
 class Rejected(reason:String):IllegalStateException(reason)
 class Permit private constructor(){companion object{internal fun issue()=Permit()}}
 private val lock=Any()
 private var owner:Permit?=null
 private var calls=0
 private var cursors=0
 private var transactions=0
 private var opened:SupportSQLiteDatabase?=null
 private var helperCreated=false
 private val grant=ThreadLocal<Permit?>()
 private data class Transaction(val authority:Permit?)
 private val txStack=ThreadLocal.withInitial{java.util.ArrayDeque<Transaction>()}
 private fun <T> withoutGrant(action:()->T):T{val prior=grant.get();grant.remove();return try{action()}finally{grant.set(prior)}}
 @Volatile internal var afterPhaseCaptureForTests:((String,Boolean)->Unit)?=null
 private fun <T> pinnedInvocation(action:(Boolean)->T):T{
  val claimed=synchronized(lock){calls++;owner!=null}
  return try{action(claimed)}finally{synchronized(lock){calls--}}
 }
 internal fun idleForTests()=synchronized(lock){calls==0&&cursors==0&&transactions==0}
 fun claim():Permit=synchronized(lock){
  if(owner!=null)throw Rejected("already_claimed")
  if(calls!=0||cursors!=0||transactions!=0)throw Rejected("busy")
  if(opened==null)throw Rejected("not_opened")
  Permit.issue().also{owner=it}
 }
 fun <T> access(permit:Permit,action:()->T):T{
  synchronized(lock){if(owner!==permit)throw Rejected("wrong_permit")}
  val previous=grant.get();grant.set(permit)
  return try{action()}finally{grant.set(previous)}
 }
 private fun <T> call(public:Boolean=false,action:()->T):T{
  synchronized(lock){if(owner!=null&&grant.get()!==owner&&!public)throw Rejected("unowned_execution");calls++}
  return try{action()}finally{synchronized(lock){calls--}}
 }
 private fun invoke(target:Any,method:Method,args:Array<out Any?>?):Any?=try{method.invoke(target,*(args?:emptyArray()))}catch(e:InvocationTargetException){throw e.targetException}
 private fun proxy(type:Class<*>,action:(Method,Array<out Any?>?)->Any?):Any=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(type)){_,method,args->action(method,args)}
 fun wrap(factory:SupportSQLiteOpenHelper.Factory)=SupportSQLiteOpenHelper.Factory{config->
  synchronized(lock){check(!helperCreated){"single_helper_required"};helperCreated=true}
  val helper=factory.create(config)
  proxy(SupportSQLiteOpenHelper::class.java){method,args->
   val public=method.name in setOf("getDatabaseName","getReadableDatabase","getWritableDatabase")
   call(public){
    val cached=synchronized(lock){if(owner!=null&&method.name in setOf("getReadableDatabase","getWritableDatabase"))opened else null}
    if(cached!=null)cached else{
     val value=invoke(helper,method,args)
     if(value is SupportSQLiteDatabase)database(value).also{synchronized(lock){opened=it}}else value
    }
   }
  } as SupportSQLiteOpenHelper
 }
 private fun sqlArg(args:Array<out Any?>?):String?=when(val first=args?.firstOrNull()){
  is String->first
  is SupportSQLiteQuery->withoutGrant{first.sql}
  else->null
 }
 private fun maintenance(sql:String?,query:Boolean):Boolean=when{
  query->sql=="SELECT * FROM room_table_modification_log WHERE invalidated = 1"
  else->sql=="UPDATE room_table_modification_log SET invalidated = 0 WHERE invalidated = 1"
 }
 private val metadata=setOf("isOpen","isReadOnly","inTransaction","isDbLockedByCurrentThread","isWriteAheadLoggingEnabled","getPath","getAttachedDbs","getVersion","getMaximumSize","getPageSize")
 private fun database(db:SupportSQLiteDatabase):SupportSQLiteDatabase = proxy(SupportSQLiteDatabase::class.java){method,args->pinnedInvocation{claimed->
  // Pin even classification/getters: claim cannot cut over between authority
  // phase capture, caller SQL metadata, sealing and actual execution.
  afterPhaseCaptureForTests?.invoke(method.name,claimed)
  val begin=method.name in setOf("beginTransaction","beginTransactionNonExclusive","beginTransactionWithListener","beginTransactionWithListenerNonExclusive")
  val end=method.name=="endTransaction"
  val tx=begin||end||method.name=="setTransactionSuccessful"
  val query=method.name=="query"
  val sql=sqlArg(args)
  var actualArgs=args
  if(claimed&&args?.firstOrNull() is SupportSQLiteQuery){
   // Never validate one sql value and let SQLite re-read a mutable object.
   // Maintenance has no placeholders; no caller bind callback is necessary.
   if(!query||!maintenance(sql,true))throw Rejected("dynamic_query_not_supported")
   actualArgs=args.copyOf().also{(it as Array<Any?>)[0]=androidx.sqlite.db.SimpleSQLiteQuery(sql!!)}
  }
  if(claimed&&method.name.contains("WithListener"))throw Rejected("listener_not_supported")
  if(end||method.name=="setTransactionSuccessful"){
   val top=txStack.get().peekLast()?:throw Rejected("transaction_not_owned")
   if(top.authority!==grant.get())throw Rejected("transaction_authority_changed")
  }
  val public=method.name in metadata||tx||maintenance(sql,query)||(method.name=="compileStatement"&&maintenance(sql,false))
  call(public){
   val value=try{invoke(db,method,actualArgs)}finally{
    // SQLite end consumes its transaction even when a listener's commit or
    // rollback throws. Listener begins after claim are refused above.
    if(end){txStack.get().removeLast();synchronized(lock){check(transactions>0);transactions--}}
   }
   when{
    begin->{txStack.get().addLast(Transaction(grant.get()));synchronized(lock){transactions++};value}
    end->value
    value is Cursor->{synchronized(lock){cursors++};cursor(value,query&&maintenance(sql,true))}
    value is SupportSQLiteStatement->statement(value,maintenance(sql,false))
    else->value
   }
  }
 }} as SupportSQLiteDatabase
 private fun statement(statement:SupportSQLiteStatement,maintenance:Boolean):SupportSQLiteStatement = proxy(SupportSQLiteStatement::class.java){method,args->
  // Bindings are never enough to widen fixed maintenance SQL (no placeholders).
  call(maintenance){invoke(statement,method,args)}
 } as SupportSQLiteStatement
 private fun cursor(cursor:Cursor,maintenance:Boolean):Cursor{
  val closed=java.util.concurrent.atomic.AtomicBoolean()
  return proxy(Cursor::class.java){method,args->
   if(method.name=="close"){
    if(closed.compareAndSet(false,true))try{invoke(cursor,method,args)}finally{synchronized(lock){cursors--}}
    null
   }else call(maintenance){invoke(cursor,method,args)}
  } as Cursor
 }
}
