package com.socrata.datacoordinator
package truth.loader

import com.socrata.datacoordinator.id.RowId

sealed abstract class Operation[+CV]
case class Insert[CV](systemId: RowId, data: Row[CV]) extends Operation[CV]
case class Update[CV](systemId: RowId, data: Row[CV]) extends Operation[CV]
case class Delete(systemId: RowId) extends Operation[Nothing]
