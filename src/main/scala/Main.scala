import com.intel.analytics.bigdl.dataset.Sample
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.T
import com.intel.analytics.zoo.common.NNContext
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.ml.feature._
import org.apache.spark.ml.linalg.SparseVector
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

import scala.collection.mutable

/**
  * Created by luyangwang on Dec, 2018
  *
  */
object Main extends App{

  Logger.getLogger("org").setLevel(Level.WARN)

  val conf = new SparkConf()
    .setAppName("stemCell")
    .setMaster("local[*]")
    .set("spark.driver.memory", "100g")
  val sc = NNContext.initNNContext(conf)
  val spark = SparkSession.builder().config(conf).getOrCreate()

  val data = spark.read.options(Map("header" -> "true", "delimiter" -> "|")).csv("./modelFiles/recRNNsample.csv")

  val skuCount = data.select("SKU_NUM").distinct().count().toInt
  val skuIndexer = new StringIndexer().setInputCol("SKU_NUM").setOutputCol("SKU_INDEX").setHandleInvalid("keep")
  val skuIndexerModel = skuIndexer.fit(data)
  val labelIndexer = new StringIndexer().setInputCol("out").setOutputCol("label").setHandleInvalid("keep")
  val ohe = new OneHotEncoder().setInputCol("SKU_INDEX").setOutputCol("vectors")

  val data1a = skuIndexerModel.transform(data).withColumn("SKU_INDEX", col("SKU_INDEX")+1)
  val data1 = ohe.transform(data1a)

  data1.show()
  data1.printSchema()

  val featureRow = data1.select("vectors").head
  val inputLayer = featureRow(0).asInstanceOf[SparseVector].size

  println(inputLayer)

  val data2 = data1.groupBy("SESSION_ID")
    .agg(collect_list("vectors").alias("item"), collect_list("SKU_INDEX").alias("sku"))

  data2.printSchema()
  data2.show()

  val maxLength = 5
  val skuPadding = Array.fill[Double](inputLayer)(0.0)
  val skuPadding1 = Array.fill[Array[Double]](maxLength)(skuPadding)
  val bcPadding = sc.broadcast(skuPadding1).value

//  val data3 = data2.rdd.map(r => {
//    val item = r.getAs[mutable.WrappedArray[SparseVector]]("item").array.map(_.toArray)
//    val sku = r.getAs[mutable.WrappedArray[java.lang.Double]]("sku").array.map(_.toDouble)
//    val item1 = bcPadding ++ item
//    val item2 = item1.takeRight(maxLength + 1)
//    val label = sku.takeRight(1).head
//    val features = item2.dropRight(1)
//    (features, label)
//  })

  def prePadding: mutable.WrappedArray[SparseVector] => Array[Array[Double]] = x => {
    val maxLength = 5
    val skuPadding = Array.fill[Double](inputLayer)(0.0)
    val skuPadding1 = Array.fill[Array[Double]](maxLength)(skuPadding)
    val item = skuPadding1 ++ x.array.map(_.toArray)
    val item2 = item.takeRight(maxLength + 1)
    val item3 = item2.dropRight(1)
    item3
  }

  def getLabel: mutable.WrappedArray[java.lang.Double] => Double = x => {
    x.takeRight(1).head
  }


  val prePaddingUDF = udf(prePadding)
  val getLabelUDF = udf(getLabel)

  val data3 = data2
    .withColumn("features", prePaddingUDF(col("item")))
    .withColumn("label", getLabelUDF(col("sku")))

  data3.show()
  data3.printSchema()


  val outSize = data3.rdd.map(_.getAs[Double]("label")).max.toInt
  println(outSize)

  val trainSample = data3.rdd.map(r => {
    val label = Tensor[Double](T(r.getAs[Double]("label")))
    val array = r.getAs[mutable.WrappedArray[mutable.WrappedArray[java.lang.Double]]]("features").array.flatten
    val vec = Tensor(array.map(_.toDouble), Array(maxLength, inputLayer))
    Sample(vec, label)
  })

  println("Sample feature print: "+ trainSample.take(1).head.feature())
  println("Sample label print: " + trainSample.take(1).head.label())
//
  val rnn = new RecRNN()
  val model = rnn.buildModel(outSize, skuCount, maxLength)
  rnn.train(model, trainSample, "./modelFiles/rnnModel", 10, 8)

}
