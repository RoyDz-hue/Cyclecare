package com.cyclecare.app.lab.store
import androidx.room.*

// NEW synthetic schema v1. NOT production Room schema9/10, no migration compatibility claim.
@Entity(tableName="lab_records",primaryKeys=["namespace","recordId"])
internal data class LabRecord(val namespace:String,val recordId:String,val payload:String)
@Entity(tableName="lab_revisions",primaryKeys=["namespace","recordId"])
internal data class LabRevision(val namespace:String,val recordId:String,val revision:Long,val tombstone:Boolean,val payloadHash:String?)
@Entity(tableName="lab_journal",primaryKeys=["namespace","operationId","recordId"])
internal data class LabJournal(val namespace:String,val operationId:String,val recordId:String,val revision:Long,val kind:String,val payload:String?,val payloadHash:String)
@Entity(tableName="lab_receipts",primaryKeys=["namespace","operationId"])
internal data class LabReceipt(val namespace:String,val operationId:String,val intentDigest:String,val resultVersion:Int,val resultJson:String)
@Entity(tableName="lab_metadata")
internal data class LabMetadata(@PrimaryKey val id:Int=1,val runId:String,val ownerDigest:String)
@Dao internal interface LabStoreDao {
 @Query("SELECT * FROM lab_metadata WHERE id=1") suspend fun metadata():LabMetadata?
 @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertMetadata(value:LabMetadata)
 @Query("SELECT * FROM lab_records") suspend fun records():List<LabRecord>
 @Query("SELECT * FROM lab_revisions") suspend fun revisions():List<LabRevision>
 @Query("SELECT * FROM lab_journal") suspend fun journals():List<LabJournal>
 @Query("SELECT * FROM lab_receipts") suspend fun receipts():List<LabReceipt>
 @Query("SELECT * FROM lab_revisions WHERE namespace=:owner AND recordId=:record LIMIT 1") suspend fun revision(owner:String,record:String):LabRevision?
 @Query("SELECT * FROM lab_receipts WHERE namespace=:owner AND operationId=:op LIMIT 1") suspend fun receipt(owner:String,op:String):LabReceipt?
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRecord(value:LabRecord)
 @Query("DELETE FROM lab_records WHERE namespace=:owner AND recordId=:record") suspend fun deleteRecord(owner:String,record:String)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRevision(value:LabRevision)
 @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun putJournal(value:LabJournal)
 @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun putReceipt(value:LabReceipt)
}
@Database(entities=[LabRecord::class,LabRevision::class,LabJournal::class,LabReceipt::class,LabMetadata::class],version=1,exportSchema=false)
internal abstract class LabReceiptDatabase:RoomDatabase(){abstract fun dao():LabStoreDao}
