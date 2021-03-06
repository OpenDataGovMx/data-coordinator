package com.socrata.datacoordinator.common.soql.jsonreps

import com.socrata.datacoordinator.truth.json.JsonColumnRep
import com.socrata.soql.types.{SoQLNull, SoQLValue, SoQLID, SoQLType}
import com.socrata.soql.environment.ColumnName
import com.rojoma.json.ast.{JNull, JString, JValue}
import com.socrata.datacoordinator.common.soql.SoQLRep
import com.socrata.datacoordinator.id.RowId

class IDRep(obfuscationContext: SoQLRep.IdObfuscationContext) extends JsonColumnRep[SoQLType, SoQLValue] {
  def fromJValue(input: JValue): Option[SoQLID] = input match {
    case JString(obfuscated) =>
      obfuscationContext.deobfuscate(obfuscated).map { r => SoQLID(r.underlying) }
    case _ =>
      None
  }

  def toJValue(value: SoQLValue): JValue = value match {
    case SoQLID(rowId) => JString(obfuscationContext.obfuscate(new RowId(rowId)))
    case SoQLNull => JNull
    case _ => stdBadValue
  }

  val representedType: SoQLType = SoQLID
}
