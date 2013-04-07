package org.json4s

import scala.annotation.switch
import java.util.concurrent.LinkedBlockingQueue

object ParserUtil {

  class ParseException(message: String, cause: Exception) extends Exception(message, cause)
  private val EOF = (-1).asInstanceOf[Char]
  def unquote(string: String): String =
    unquote(new Buffer(new java.io.StringReader(string), false))

  private[json4s] def unquote(buf: Buffer): String = {
    def unquote0(buf: Buffer, sb: java.lang.StringBuilder): String = {
      val s = sb
      var c = '\\'
      while (c != '"') {
        if (c == '\\') {
          val n = buf.next
          if (n == '"') s.append('"')
          else if (n == '\\') s.append('\\')
          else if (n == '/') s.append('/')
          else if (n == 'b') s.append('\b')
          else if (n == 'f') s.append('\f')
          else if (n == 'n') s.append('\n')
          else if (n == 'r') s.append('\r')
          else if (n == 't') s.append('\t')
          else if (n == 'u') {
            val chars = Array(buf.next, buf.next, buf.next, buf.next)
            val codePoint = Integer.parseInt(new String(chars), 16)
            s.appendCodePoint(codePoint)
          } else s.append('\\')
        } else s.append(c)
        c = buf.next
      }
      s.toString
    }

    buf.eofIsFailure = true
    buf.mark
    var c = buf.next
    while (c != '"') {
      if (c == '\\') {
        val s = unquote0(buf, new java.lang.StringBuilder(buf.substring))
        buf.eofIsFailure = false
        return s
      }
      c = buf.next
    }
    buf.eofIsFailure = false
    buf.substring
  }

  /* Buffer used to parse JSON.
   * Buffer is divided to one or more segments (preallocated in Segments pool).
   */
  private[json4s] class Buffer(in: java.io.Reader, closeAutomatically: Boolean) {
    var offset = 0
    var curMark = -1
    var curMarkSegment = -1
    var eofIsFailure = false
    private[this] var segments: List[Segment] = List(Segments.apply())
    private[this] var segment: Array[Char] = segments.head.seg
    private[this] var cur = 0 // Pointer which points current parsing location
    private[this] var curSegmentIdx = 0 // Pointer which points current segment

    def mark = { curMark = cur; curMarkSegment = curSegmentIdx }
    def back = cur = cur-1

    def next: Char = {
      if (cur == offset && read < 0) {
        if (eofIsFailure) throw new ParseException("unexpected eof", null) else EOF
      } else {
        val c = segment(cur)
        cur += 1
        c
      }
    }

    def substring = {
      if (curSegmentIdx == curMarkSegment) new String(segment, curMark, cur-curMark-1)
      else { // slower path for case when string is in two or more segments
        var parts: List[(Int, Int, Array[Char])] = Nil
        var i = curSegmentIdx
        while (i >= curMarkSegment) {
          val s = segments(i).seg
          val start = if (i == curMarkSegment) curMark else 0
          val end = if (i == curSegmentIdx) cur else s.length+1
          parts = (start, end, s) :: parts
          i = i-1
        }
        val len = parts.map(p => p._2 - p._1 - 1).foldLeft(0)(_ + _)
        val chars = new Array[Char](len)
        i = 0
        var pos = 0

        while (i < parts.size) {
          val (start, end, b) = parts(i)
          val partLen = end-start-1
          System.arraycopy(b, start, chars, pos, partLen)
          pos = pos + partLen
          i = i+1
        }
        new String(chars)
      }
    }

    def near = new String(segment, (cur-20) max 0, (cur + 1) min Segments.segmentSize)

    def release = segments.foreach(Segments.release)

    private[json4s] def automaticClose = if (closeAutomatically) in.close

    private[this] def read = {
      if (offset >= segment.length) {
        val newSegment = Segments.apply()
        offset = 0
        segment = newSegment.seg
        segments = segments ::: List(newSegment)
        curSegmentIdx = segments.length - 1
      }

      val length = in.read(segment, offset, segment.length-offset)
      cur = offset
      offset += length
      length
    }
  }

  /* A pool of preallocated char arrays.
   */
  private[json4s] object Segments {
    import java.util.concurrent.ArrayBlockingQueue
    import java.util.concurrent.atomic.AtomicInteger

    var segmentSize = 1000
    private[this] val maxNumOfSegments = 10000
    private[this] val segmentCount = new AtomicInteger(0)
    private[this] val segments = new LinkedBlockingQueue[Segment](maxNumOfSegments)
    def clear = segments.clear

    def apply(): Segment = {
      val s = acquire
      // Give back a disposable segment if pool is exhausted.
      if (s != null) s else DisposableSegment(new Array(segmentSize))
    }

    private[this] def acquire: Segment = {
      val curCount = segmentCount.get
      val createNew =
        if (segments.size == 0 && curCount < maxNumOfSegments)
          segmentCount.compareAndSet(curCount, curCount + 1)
        else false

      if (createNew) RecycledSegment(new Array(segmentSize)) else segments.poll
    }

    def release(s: Segment) = s match {
      case _: RecycledSegment => segments.offer(s)
      case _ =>
    }
  }

  sealed trait Segment {
    val seg: Array[Char]
  }
  case class RecycledSegment(seg: Array[Char]) extends Segment
  case class DisposableSegment(seg: Array[Char]) extends Segment


  private[this] val BrokenDouble = BigDecimal("2.2250738585072012e-308")

  @inline private[json4s] def parseDouble(s: String) = {
    val d = BigDecimal(s)
    if (d == BrokenDouble) sys.error("Error parsing 2.2250738585072012e-308")
    else d.doubleValue
  }

}