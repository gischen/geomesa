/*
 * Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0 which
 * accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.curve

import org.locationtech.geomesa.curve.XZ2SFC.Bounds
import org.locationtech.sfcurve.IndexRange

import scala.collection.mutable.ArrayBuffer

class XZ2SFC(g: Short) {

  // TODO see if we can use Ints
  // require(g < 13, "precision > 12 is not supported by Ints")

  def index(xmin: Double, ymin: Double, xmax: Double, ymax: Double): Long = {
    validateBounds(xmin, ymin, xmax, ymax)
    val bounds = Bounds(normalizeLon(xmin),  normalizeLat(ymin), normalizeLon(xmax), normalizeLat(ymax))
    index(bounds)
  }

  private def index(bounds: Bounds): Long = {
    val l1 = math.min(g, math.floor(math.log(math.max(bounds.width, bounds.height)) / math.log(0.5)).toInt)

    def predicate(value: Double, wh: Double): Boolean = math.floor((value / l1) + 2) * l1 <= value + wh

    val length = if (predicate(bounds.xmin, bounds.width) && predicate(bounds.ymin, bounds.height)) l1 else l1 + 1

    sequenceCode(bounds.xmin, bounds.ymin, length)
  }

  // normalize to [0, 1]
  private def normalizeLon(x: Double): Double = (x + 180.0) / 360.0
  private def normalizeLat(y: Double): Double = (y + 90.0) / 180.0

  private def validateBounds(xmin: Double, ymin: Double, xmax: Double, ymax: Double): Unit = {
    require(xmin <= 180.0 && xmin >= -180.00 && xmax <= 180.0 && xmax >= -180.00 &&
        ymin <= 90.0 && ymin >= -90.00 && ymax <= 90.0 && ymax >= -90.00,
      s"Bounds must be within [-180 180] [-90 90]: [$xmin $xmax] [$ymin $ymax]"
    )
  }

  private def sequenceCode(x: Double, y: Double, length: Int): Long = {
    var xmin = 0.0
    var ymin = 0.0
    var xmax = 1.0
    var ymax = 1.0

    var cs = 1L

    var i = 0
    while (i < length) {
      val xCenter = (xmin + xmax) / 2.0
      val yCenter = (ymin + ymax) / 2.0
      (x < xCenter, y < yCenter) match {
        case (true,  true)  => /* cs += 0L                                    */ xmax = xCenter; ymax = yCenter
        case (false, true)  => cs += 1L * (math.pow(4, g - i).toLong - 1L) / 3L; xmin = xCenter; ymax = yCenter
        case (true,  false) => cs += 2L * (math.pow(4, g - i).toLong - 1L) / 3L; xmax = xCenter; ymin = yCenter
        case (false, false) => cs += 3L * (math.pow(4, g - i).toLong - 1L) / 3L; xmin = xCenter; ymin = yCenter
      }
      require((math.pow(4, g - i).toLong - 1L) % 3L == 0)
      i += 1
    }

    cs
  }

  def ranges(bounds: Seq[(Double, Double, Double, Double)], maxRanges: Option[Int] = None): Seq[IndexRange] = {
    val normalizedBounds = bounds.map { case (xmin, ymin, xmax, ymax) =>
      validateBounds(xmin, ymin, xmax, ymax)
      Bounds(normalizeLon(xmin),  normalizeLat(ymin), normalizeLon(xmax),normalizeLat(ymax))
    }
    ranges(normalizedBounds, maxRanges.getOrElse(Int.MaxValue))
  }

  private def ranges(bounds: Seq[Bounds], rangeStop: Int): Seq[IndexRange] = {

    import XZ2SFC.LevelTerminator

    // stores our results - initial size of 100 in general saves us some re-allocation
    val ranges = new java.util.ArrayList[IndexRange](100)

    // values remaining to process - initial size of 100 in general saves us some re-allocation
    val remaining = new java.util.ArrayDeque[Bounds](100)

    // checks if a range is contained in the search space
    def isContained(quad: Bounds): Boolean = {
      var i = 0
      while (i < bounds.length) {
        if (bounds(i).contains(quad)) {
          return true
        }
        i += 1
      }
      false
    }

    // checks if a range overlaps the search space
    def isOverlapped(quad: Bounds): Boolean = {
      var i = 0
      while (i < bounds.length) {
        if (bounds(i).overlaps(quad)) {
          return true
        }
        i += 1
      }
      false
    }

    // checks a single value and either:
    //   eliminates it as out of bounds
    //   adds it to our results as fully matching, or
    //   queues up it's children for further processing
    def checkValue(quad: Bounds, level: Short): Unit = {
      if (isContained(quad)) {
        // whole range matches, happy day
        val (min, max) = sequenceInterval(quad.xmin, quad.ymin, level, partial = false)
        ranges.add(IndexRange(min, max, contained = true))
      } else if (isOverlapped(quad)) {
        // some portion of this range is excluded
        // add the partial match and queue up each sub-range for processing
        val (min, max) = sequenceInterval(quad.xmin, quad.ymin, level, partial = true)
        ranges.add(IndexRange(min, max, contained = false))
        quad.children.foreach(remaining.add)
      }
    }

    def sequenceInterval(x: Double, y: Double, level: Short, partial: Boolean): (Long, Long) = {
      val min = sequenceCode(x, y, level)
      // there should be this many values starting with this prefix: (math.pow(4, g - level + 1).toLong - 1L) / 3L
      // so take the minimum sequence code and add that
      val max = if (partial) { min } else { min + (math.pow(4, g - level + 1).toLong - 1L) / 3L }
      (min, max)
    }

    // initial level
    Bounds(0.0, 0.0, 1.0, 1.0).children.foreach(remaining.add)
    remaining.add(LevelTerminator)

    // level of recursion
    var level: Short = 1

    while (level <= g && !remaining.isEmpty && ranges.size < rangeStop) {
      val next = remaining.poll
      if (next.eq(LevelTerminator)) {
        // we've fully processed a level, increment our state
        if (!remaining.isEmpty) {
          level = (level + 1).toShort
          remaining.add(LevelTerminator)
        }
      } else {
        checkValue(next, level)
      }
    }

    // bottom out and get all the ranges that partially overlapped but we didn't fully process
    while (!remaining.isEmpty) {
      val quad = remaining.poll
      if (quad.eq(LevelTerminator)) {
        level = (level + 1).toShort
      } else {
        val (min, max) = sequenceInterval(quad.xmin, quad.ymin, level, partial = false)
        ranges.add(IndexRange(min, max, contained = false))
      }
    }

    // TODO we could use the algorithm from the XZ paper instead
    // we've got all our ranges - now reduce them down by merging overlapping values
    ranges.sort(IndexRange.IndexRangeIsOrdered)

    var current = ranges.get(0) // note: should always be at least one range
    val result = ArrayBuffer.empty[IndexRange]
    var i = 1
    while (i < ranges.size()) {
      val range = ranges.get(i)
      if (range.lower <= current.upper + 1) {
        // merge the two ranges
        current = IndexRange(current.lower, math.max(current.upper, range.upper), current.contained && range.contained)
      } else {
        // append the last range and set the current range for future merging
        result.append(current)
        current = range
      }
      i += 1
    }
    // append the last range - there will always be one left that wasn't added
    result.append(current)

    result
  }
}

object XZ2SFC {

  val DefaultRecurse = 10

  // indicator that we have searched a full level of the quad/oct tree
  private val LevelTerminator = Bounds(-1.0, -1.0, -1.0, -1.0)

  private case class Bounds(xmin: Double, ymin: Double, xmax: Double, ymax: Double) {

    lazy val width  = xmax - xmin
    lazy val height = ymax - ymin

    // TODO maybe this needs to account for expanded box?
    def contains(element: Bounds) =
      xmin <= element.xmin && ymin <= element.ymin && xmax >= element.xmax + width && ymax >= element.ymax + height

    def overlaps(element: Bounds) =
      xmax >= element.xmin && ymax >= element.ymin && xmin <= element.xmax + width && ymin <= element.ymax + height


    def children: Seq[Bounds] = {
      val xCenter = (xmin + xmax) / 2.0
      val yCenter = (ymin + ymax) / 2.0
      val c0 = copy(xmax = xCenter, ymax = yCenter)
      val c1 = copy(xmin = xCenter, ymax = yCenter)
      val c2 = copy(xmax = xCenter, ymin = yCenter)
      val c3 = copy(xmin = xCenter, ymin = yCenter)
      Seq(c0, c1, c2, c3)
    }
  }
}