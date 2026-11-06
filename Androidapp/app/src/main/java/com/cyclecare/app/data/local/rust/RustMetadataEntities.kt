package com.cyclecare.app.data.local.rust
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
/** Additive metadata candidate, not registered in production database yet. */
@Entity(tableName="rust_float_bits")
internal data class RustFloatBits(@PrimaryKey val dateString:String,val normalizedHash:String,val bbtBits:Long,val sleepBits:Long)
/** Local completion evidence is NEVER a server ACK; outbox deletion cannot
 * delete this receipt. Full owner/session/generation binding stays in digest.
 */
@Entity(tableName="rust_commit_receipts",primaryKeys=["namespace","operationId"])
internal data class RustCommitReceipt(val namespace:String,val operationId:String,val intentDigest:String,val version:Int,val resultJson:String)
@Dao
internal interface RustMetadataDao {
 @Query("SELECT * FROM rust_float_bits WHERE dateString=:date LIMIT 1")
 fun bits(date:String):RustFloatBits?
 @Insert(entity=RustFloatBits::class,onConflict=OnConflictStrategy.REPLACE)
 fun putBits(bits:RustFloatBits)
 @Query("SELECT * FROM rust_commit_receipts WHERE namespace=:namespace AND operationId=:operation LIMIT 1")
 fun receipt(namespace:String,operation:String):RustCommitReceipt?
 @Insert(entity=RustCommitReceipt::class,onConflict=OnConflictStrategy.ABORT)
 fun putReceipt(receipt:RustCommitReceipt)
}

/** Staging schema remains unregistered in production; READY visibility is local. */
@Entity(tableName="rust_domain_epoch")
internal data class RustDomainEpoch(@PrimaryKey val id:Int,val value:Long)
@Entity(tableName="rust_enrollments",primaryKeys=["namespace","operation"])
internal data class RustEnrollment(val namespace:String,val operation:String,val binding:String,val epoch:Long,val state:String,val nextRecord:String,val recordCount:Int,@androidx.room.ColumnInfo(defaultValue="''") val nextSend:String="")
@Entity(tableName="rust_enrollment_rows",primaryKeys=["namespace","operation","record"])
internal data class RustEnrollmentRow(val namespace:String,val operation:String,val record:String,val payload:String,val physical:String,val revision:Long,val serverRevision:Long,val child:String,@androidx.room.ColumnInfo(defaultValue="0") val attempts:Int=0,@androidx.room.ColumnInfo(defaultValue="0") val nextAttempt:Long=0)
@Entity(tableName="rust_enrollment_receipts",primaryKeys=["namespace","operation","record"])
internal data class RustEnrollmentReceipt(val namespace:String,val operation:String,val record:String,val child:String,val serverRevision:Long,val receiptHash:String,@androidx.room.ColumnInfo(defaultValue="0") val adopted:Int=0)
