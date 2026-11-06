package com.cyclecare.app.lab.store
import java.io.File

/** Accept Android ancestor aliases, never follow a fixture's own final symlink. */
internal object LabFixturePath {
 fun resolve(directory:File,filename:String,isSymbolicLink:(File)->Boolean):File {
  require(Regex("cyclecare_lab_synthetic_[0-9a-f]{32}_[0-9a-f]{64}\\.db").matches(filename))
  val root=directory.canonicalFile
  val candidate=File(root,filename)
  require(!isSymbolicLink(candidate))
  // Root is already canonical. A different final canonical path therefore means
  // this component is a symlink (including a link to another contained fixture).
  require(candidate.canonicalFile==candidate.absoluteFile)
  require(candidate.canonicalFile.parentFile==root)
  return candidate
 }
}
