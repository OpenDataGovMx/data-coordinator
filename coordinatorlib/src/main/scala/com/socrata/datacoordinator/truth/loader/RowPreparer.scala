package com.socrata.datacoordinator
package truth.loader

trait RowPreparer[CV] {
  def prepareForInsert(row: Row[CV], sid: RowId): Row[CV]
  def prepareForUpdate(row: Row[CV]): Row[CV]
}