package com.socrata.datacoordinator.common.soql

import scala.collection.JavaConverters._

import com.socrata.soql.types._
import com.socrata.datacoordinator.truth.{SimpleRowUserIdMap, RowUserIdMap, TypeContext}
import com.socrata.datacoordinator.id.RowId
import com.socrata.datacoordinator.truth.metadata.{DatasetInfo, TypeNamespace}
import com.socrata.soql.environment.TypeName

object SoQLTypeContext extends TypeContext[SoQLType, SoQLValue] {
  def isNull(value: SoQLValue): Boolean = SoQLNull == value

  def makeValueFromSystemId(id: RowId): SoQLValue = SoQLID(id.underlying)

  def makeSystemIdFromValue(id: SoQLValue): RowId = new RowId(id.asInstanceOf[SoQLID].value)

  def nullValue: SoQLValue = SoQLNull

  val typeNamespace: TypeNamespace[SoQLType] = new TypeNamespace[SoQLType] {
    private val typesByTypeName = SoQLType.typesByName.values.foldLeft(Map.empty[String, SoQLType]) { (acc, typ) =>
      acc + (typ.name.caseFolded -> typ)
    }
    def typeForName(datasetInfo: DatasetInfo, name: String) = typesByTypeName(name)

    def nameForType(typ: SoQLType) = typ.name.caseFolded

    def typeForUserType(name: TypeName): Option[SoQLType] = typesByTypeName.get(name.caseFolded)

    def userTypeForType(typ: SoQLType) = typ.name
  }

  def makeIdMap[T](idColumnType: SoQLType): RowUserIdMap[SoQLValue, T] =
    if(idColumnType == SoQLText) {
      new RowUserIdMap[SoQLValue, T] {
        val map = new java.util.HashMap[String, (SoQLValue, T)]

        def put(x: SoQLValue, v: T) {
          val s = x.asInstanceOf[SoQLText].value
          map.put(s.toLowerCase, (x, v))
        }

        def apply(x: SoQLValue): T = {
          val s = x.asInstanceOf[SoQLText].value
          val k = s.toLowerCase
          if(map.containsKey(k)) map.get(k)._2
          else throw new NoSuchElementException
        }

        def get(x: SoQLValue): Option[T] = {
          val s = x.asInstanceOf[SoQLText].value
          val k = s.toLowerCase
          if(map.containsKey(k)) Some(map.get(k)._2)
          else None
        }

        def clear() {
          map.clear()
        }

        def contains(x: SoQLValue): Boolean = {
          val s = x.asInstanceOf[SoQLText].value
          map.containsKey(s.toLowerCase)
        }

        def isEmpty: Boolean = map.isEmpty

        def size: Int = map.size

        def foreach(f: (SoQLValue, T) => Unit) {
          val it = map.values.iterator
          while(it.hasNext) {
            val (k, v) = it.next()
            f(k, v)
          }
        }

        def valuesIterator: Iterator[T] =
          map.values.iterator.asScala.map(_._2)
      }
    } else {
      new SimpleRowUserIdMap[SoQLValue, T]
    }
}
