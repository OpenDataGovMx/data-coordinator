package com.socrata.datacoordinator.packets

import java.nio.ByteBuffer

class Packet private (val buf: ByteBuffer, dummy: Int) {
  def this(buffer: ByteBuffer) = this(buffer.asReadOnlyBuffer, 0)

  assert(buf.remaining >= 4)
  assert(buf.getInt(0) == buffer.remaining(), "Size was %d; remaining is %d".format(buffer.getInt(0), buffer.remaining))
  def buffer = buf.duplicate()
  def data = buffer.position(4).asInstanceOf[ByteBuffer] /* yay no "this.type" in Java */ .slice()
  def dataSize = buf.remaining - 4

  override def equals(o: Any) = o match {
    case that: Packet =>
      this.buf == that.buf
    case _ =>
      false
  }
}

object Packet {
  val empty = new PacketOutputStream().packet()
}