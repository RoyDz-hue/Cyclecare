package com.cyclecare.app.lab.store
import org.json.JSONArray
import org.json.JSONObject

/** Typed, synthetic-only contents. Never includes envelope, alias secret or key bytes. */
internal data class LabSyntheticSnapshot(val rows:List<LabRecord>,val revisions:List<LabRevision>,val journal:List<LabJournal>,val receipts:List<LabReceipt>,val metadata:LabMetadata?) {
 fun json():JSONObject {
  fun array(items:List<JSONObject>)=JSONArray().apply{items.forEach{put(it)}}
  return JSONObject().put("rows",array(rows.sortedBy{it.recordId}.map{JSONObject().put("namespace",it.namespace).put("record",it.recordId).put("payload",it.payload).put("hash",labHash(it.payload))}))
   .put("revisions",array(revisions.sortedBy{it.recordId}.map{JSONObject().put("namespace",it.namespace).put("record",it.recordId).put("revision",it.revision).put("tombstone",it.tombstone).put("hash",it.payloadHash?:JSONObject.NULL)}))
   .put("journal",array(journal.sortedWith(compareBy({it.operationId},{it.recordId})).map{JSONObject().put("namespace",it.namespace).put("operation",it.operationId).put("record",it.recordId).put("revision",it.revision).put("kind",it.kind).put("payload",it.payload?:JSONObject.NULL).put("hash",it.payloadHash)}))
   .put("receipts",array(receipts.sortedBy{it.operationId}.map{JSONObject().put("namespace",it.namespace).put("operation",it.operationId).put("digest",it.intentDigest).put("version",it.resultVersion).put("result",it.resultJson)}))
   .put("metadata",metadata?.let{JSONObject().put("id",it.id).put("run",it.runId).put("ownerDigest",it.ownerDigest)}?:JSONObject.NULL)
 }
 fun digest()=labHash(json().toString())
 fun revision(record:String)=revisions.singleOrNull{it.recordId==record}?.revision?:0L
}
