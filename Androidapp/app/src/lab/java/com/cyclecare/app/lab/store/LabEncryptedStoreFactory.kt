package com.cyclecare.app.lab.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Room
import androidx.room.withTransaction
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Source-only lab adapter. Nothing calls this factory yet. No production defaults. */
internal object LabEncryptedStoreFactory {
 suspend fun open(context:Context,config:SyntheticFixtureConfig,identity:LabIdentity,diagnostic:LabSafeDiagnostic?=null):LabEncryptedStore {
  // MUST precede path/prefs/Keystore/native/library/Room access.
  diagnostic?.factoryStep=LabFactoryStep.VALIDATE
  config.validate(context.packageName);identity.validate();require(config.ownerDigest==labHash(identity.namespace)) { "Synthetic owner binding mismatch" }
  diagnostic?.factoryStep=LabFactoryStep.DIRECTORY
  val directory=context.applicationContext.getDir("cyclecare_lab_synthetic",Context.MODE_PRIVATE)
  diagnostic?.factoryStep=LabFactoryStep.PATH
  val file=LabFixturePath.resolve(directory,config.filename){candidate->
   try{android.system.OsConstants.S_ISLNK(android.system.Os.lstat(candidate.absolutePath).st_mode)}catch(e:android.system.ErrnoException){if(e.errno==android.system.OsConstants.ENOENT)false else throw e}
  }
  // New resources recorded before any creation. Existing file requires matching registry.
  diagnostic?.factoryStep=LabFactoryStep.REGISTRY
  val registry=context.getSharedPreferences("cyclecare_lab_synthetic_registry",Context.MODE_PRIVATE)
  val binding=config.runId+":"+config.ownerDigest+":"+config.alias+":"+config.prefs
  require(!file.exists()||registry.getString(config.filename,null)==binding) { "Unregistered existing synthetic file" }
  require(registry.getString(config.filename,null).let{it==null||it==binding}) { "Fixture registry mismatch" }
  check(registry.edit().putString(config.filename,binding).commit())
  val existed=file.exists()
  diagnostic?.factoryStep=LabFactoryStep.ENVELOPE
  val prefs=context.getSharedPreferences(config.prefs,Context.MODE_PRIVATE)
  // Existing DB without envelope must fail BEFORE creating/replacing wrapping key.
  val wrapped=prefs.getString("wrapped",null)
  require(!existed||wrapped!=null) { "Existing encrypted fixture has no key envelope" }
  diagnostic?.factoryStep=LabFactoryStep.KEY_LOOKUP
  val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  val existing=ks.getKey(config.alias,null) as? SecretKey
  require(wrapped==null||existing!=null) { "Existing envelope key unavailable" }
  diagnostic?.factoryStep=LabFactoryStep.KEY_GENERATE
  val key=existing?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
   init(KeyGenParameterSpec.Builder(config.alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
  }.generateKey()
  val secret:ByteArray=if(wrapped!=null){diagnostic?.factoryStep=LabFactoryStep.UNWRAP;val all=Base64.decode(wrapped,Base64.NO_WRAP);require(all.size>12);Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,all.copyOfRange(0,12)));doFinal(all.copyOfRange(12,all.size))}}else{diagnostic?.factoryStep=LabFactoryStep.WRAP;
   val raw=ByteArray(32).also{SecureRandom().nextBytes(it)}
   val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
   val encrypted=cipher.iv+cipher.doFinal(raw)
   check(prefs.edit().putString("wrapped",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit());raw
  }
  diagnostic?.factoryStep=LabFactoryStep.SECRET
  require(secret.size==32)
  val retained=RetainedFactorySecret(secret)
  var db:LabReceiptDatabase?=null
  try{
  diagnostic?.factoryStep=LabFactoryStep.NATIVE_LOAD
   System.loadLibrary("sqlcipher")
  diagnostic?.factoryStep=LabFactoryStep.ROOM_BUILD
   db=Room.databaseBuilder(context.applicationContext,LabReceiptDatabase::class.java,file.absolutePath).openHelperFactory(SupportOpenHelperFactory(retained.handOff())).build()
   // Force real open/schema validation before token emission. No destructive fallback.
  diagnostic?.factoryStep=LabFactoryStep.INTEGRITY
   db.openHelper.writableDatabase.query("PRAGMA integrity_check").use{check(it.moveToFirst()&&it.getString(0)=="ok")}
  diagnostic?.factoryStep=LabFactoryStep.METADATA
   db.withTransaction{val m=db.dao().metadata();if(m==null){check(!existed);db.dao().insertMetadata(LabMetadata(runId=config.runId,ownerDigest=config.ownerDigest))}else check(m.runId==config.runId&&m.ownerDigest==config.ownerDigest)}
   val adapter=LabEncryptedStore(db,identity,Mutex(),retained)
  diagnostic?.factoryStep=LabFactoryStep.ROW_VALIDATION
   adapter.validateOpen()
  diagnostic?.factoryStep=LabFactoryStep.READY
   return adapter
  }catch(e:Exception){db?.close();retained.close();throw IllegalStateException("Synthetic encrypted open failed; no fallback",e)}finally{secret.fill(0)}
 }
}
