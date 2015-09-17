package org.mrgeo.mapalgebra.unarymath

import java.awt.image.WritableRaster
import java.io.IOException

import org.apache.spark.SparkContext
import org.mrgeo.data.raster.RasterWritable
import org.mrgeo.data.rdd.RasterRDD
import org.mrgeo.mapalgebra.parser.ParserNode
import org.mrgeo.mapalgebra.raster.RasterMapOp
import org.mrgeo.mapalgebra.{MapOp, MapOpRegistrar}
import org.mrgeo.utils.SparkUtils

object IsNodataMapOp extends MapOpRegistrar {
  override def register: Array[String] = {
    Array[String]("isNodata", "isNull")
  }
  override def apply(node:ParserNode, variables: String => Option[ParserNode], protectionLevel:String = null): MapOp =
    new IsNodataMapOp(node, variables, protectionLevel)
}

class IsNodataMapOp extends RawUnaryMathMapOp {

  var nodata:Double = Double.NegativeInfinity
  private[unarymath] def this(node:ParserNode, variables: String => Option[ParserNode], protectionLevel:String = null) = {
    this()

    initialize(node, variables, protectionLevel)
  }

  // Unfortunately
  override def execute(context: SparkContext): Boolean = {

    // our metadata is the same as the raster
    val meta = input.get.metadata().get

    val rdd = input.get.rdd() getOrElse (throw new IOException("Can't load RDD! Ouch! " + input.getClass.getName))

    // copy this here to avoid serializing the whole mapop
    val nodata = metadata() match {
    case Some(metadata) => metadata.getDefaultValue(0)
    case _ => Double.NaN
    }

    rasterRDD = Some(RasterRDD(rdd.map(tile => {
      val raster = RasterWritable.toRaster(tile._2).asInstanceOf[WritableRaster]

      for (y <- 0 until raster.getHeight) {
        for (x <- 0 until raster.getWidth) {
          for (b <- 0 until raster.getNumBands) {
            val v = raster.getSampleDouble(x, y, b)
            if (RasterMapOp.isNodata(v, nodata)) {
              raster.setSample(x, y, b, 0)
            }
            else {
              raster.setSample(x, y, b, 1)
            }
          }
        }
      }
      (tile._1, RasterWritable.toWritable(raster))
    })))

    metadata(SparkUtils.calculateMetadata(rasterRDD.get, meta.getMaxZoomLevel, nodata))

    true
  }


  override private[unarymath] def function(a: Double): Double = { Double.NaN }
}
