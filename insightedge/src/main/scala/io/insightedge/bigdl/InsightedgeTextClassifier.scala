//   scalastyle:off

package io.insightedge.bigdl

import java.io.File
import java.util

import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.dataset._
import com.intel.analytics.bigdl.example.utils.SimpleTokenizer._
import com.intel.analytics.bigdl.example.utils.{SimpleTokenizer, WordMeta}
import com.intel.analytics.bigdl.nn.abstractnn.Activity
import com.intel.analytics.bigdl.nn.{ClassNLLCriterion, _}
import com.intel.analytics.bigdl.optim._
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.Engine
import com.j_spaces.core.client.SQLQuery
import io.insightedge.bigdl.model.{Category, Text}
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.io.Source
import scala.language.existentials

import org.insightedge.spark.context.InsightEdgeConfig
import org.insightedge.spark.implicits.basic._


/**
  * @author Danylo_Hurin.
  */
class InsightedgeTextClassifier(param: IeAbstractTextClassificationParams) extends Serializable {

  val log: Logger = LoggerFactory.getLogger(this.getClass)
  val gloveDir = s"${param.baseDir}/glove.6B/"
  val textDataDir = s"${param.baseDir}/20_newsgroup/"
  var classNum = -1
  private val gloveFile = s"glove.6B.${Consts.embeddingsDimensions}d.txt"

  /**
    * Load the pre-trained word2Vec
    *
    * @return A map from word to vector
    */
  def buildWord2Vec(word2Meta: Map[String, WordMeta]): Map[Float, Array[Float]] = {
    log.info("Indexing word vectors.")
    val preWord2Vec = MMap[Float, Array[Float]]()
    val gloveEmbeddingsFile = s"$gloveDir/$gloveFile"
    for (line <- Source.fromFile(gloveEmbeddingsFile, "ISO-8859-1").getLines) {
      val wordAndVectorValues: Array[String] = line.split(" ")
      val word: String = wordAndVectorValues(0)
      if (word2Meta.contains(word)) {
        val vectorValues: Array[String] = wordAndVectorValues.slice(1, wordAndVectorValues.length)
        val coefs: Array[Float] = vectorValues.map(_.toFloat)
        preWord2Vec.put(word2Meta(word).index.toFloat, coefs)
      }
    }
    log.info(s"Found ${preWord2Vec.size} word vectors.")
    preWord2Vec.toMap
  }

  /**
    * Load the pre-trained word2Vec
    *
    * @return A map from word to vector
    */
  def buildWord2VecWithIndex(word2Meta: Map[String, Int]): Map[Float, Array[Float]] = {
    log.info("Indexing word vectors.")
    val preWord2Vec = MMap[Float, Array[Float]]()
    val filename = s"$gloveDir/$gloveFile"
    for (line <- Source.fromFile(filename, "ISO-8859-1").getLines) {
      val values = line.split(" ")
      val word = values(0)
      if (word2Meta.contains(word)) {
        val coefs = values.slice(1, values.length).map(_.toFloat)
        preWord2Vec.put(word2Meta(word).toFloat, coefs)
      }
    }
    log.info(s"Found ${preWord2Vec.size} word vectors.")
    preWord2Vec.toMap
  }


  /**
    * Load the training data from the given baseDir
    *
    * @return An array of sample
    */
  private def loadRawData(): (Seq[(String, Int)], Map[String, Int]) = {
    val texts = ArrayBuffer[String]()
    val labels = ArrayBuffer[Int]()
    // category is a string name, label is it's index
    val categoryToLabel = new util.HashMap[String, Int]()
    val categoryPathList = new File(textDataDir).listFiles().filter(_.isDirectory).toList.sorted

    categoryPathList.foreach { categoryPath =>
      val labelId: Int = categoryToLabel.size() + 1 // one-base index
      categoryToLabel.put(categoryPath.getName, labelId)
      val textFiles = categoryPath.listFiles()
        .filter(_.isFile).filter(_.getName.forall(Character.isDigit)).sorted
      textFiles.foreach { file =>
        val source = Source.fromFile(file, "ISO-8859-1")
        val text = try source.getLines().toList.mkString("\n") finally source.close()
        texts.append(text)
        labels.append(labelId)
      }
    }
    this.classNum = labels.toSet.size
    log.info(s"Found ${texts.length} texts.")
    log.info(s"Found $classNum classes")

    (texts.zip(labels), categoryToLabel.asScala.toMap)
  }

  /**
    * Go through the whole data set to gather some meta info for the tokens.
    * Tokens would be discarded if the frequency ranking is less then maxWordsNum
    */
  def analyzeTexts(dataRdd: RDD[(String, Float)])
  : (Map[String, WordMeta], Map[Float, Array[Float]]) = {
    val tokens: RDD[String] = dataRdd.flatMap { case (text: String, label: Float) =>
      SimpleTokenizer.toTokens(text)
    }
    val tokensCount: RDD[(String, Int)] = tokens.map(word => (word, 1)).reduceByKey(_ + _)
    // Remove the top 10 words roughly, you might want to fine tuning this.
    val frequencies: Array[(String, Int)] = tokensCount
      .sortBy(-_._2).collect().slice(10, param.maxWordsNum)

    val indexes: Seq[Int] = Range(1, frequencies.length)
    val word2Meta: Map[String, WordMeta] = frequencies.zip(indexes).map { (item: ((String, Int), Int)) =>
      (item._1._1 /*word*/ , WordMeta(item._1._2 /*count*/ , item._2 /*index*/))
    }.toMap
    (word2Meta, buildWord2Vec(word2Meta))
  }

  def buildWord2Meta(dataRdd: RDD[(String)]): Map[String, WordMeta] = {
    val tokens: RDD[String] = dataRdd.flatMap { text: String =>
      SimpleTokenizer.toTokens(text)
    }
    val tokensCount: RDD[(String, Int)] = tokens.map(word => (word, 1)).reduceByKey(_ + _)
    // Remove the top 10 words roughly, you might want to fine tuning this.
    val frequencies: Array[(String, Int)] = tokensCount
      .sortBy(-_._2).collect().slice(10, param.maxWordsNum)

    val indexes: Seq[Int] = Range(1, frequencies.length)
    val word2Meta: Map[String, WordMeta] = frequencies.zip(indexes).map { (item: ((String, Int), Int)) =>
      (item._1._1 /*word*/ , WordMeta(item._1._2 /*count*/ , item._2 /*index*/))
    }.toMap
    word2Meta
  }

  /**
    * Create train and val RDDs from input
    */
//  def getData(sc: SparkContext): (Array[RDD[(Array[Array[Float]], Float)]],
//    Map[String, WordMeta],
//    Map[Float, Array[Float]]) = {
//
//    val sequenceLen = param.maxSequenceLength
//    val embeddingDim = param.embeddingDim
//    val trainingSplit = param.trainingSplit
//    // For large dataset, you might want to get such RDD[(String, Float)] from HDFS
//    val dataRdd = sc.parallelize(loadRawData(), param.partitionNum)
//    val (word2Meta, word2Vec) = analyzeTexts(dataRdd)
//    val word2MetaBC = sc.broadcast(word2Meta)
//    val word2VecBC = sc.broadcast(word2Vec)
//    val vectorizedRdd = dataRdd
//      .map { case (text, label) => (toTokens(text, word2MetaBC.value), label) }
//      .map { case (tokens, label) => (shaping(tokens, sequenceLen), label) }
//      .map { case (tokens, label) => (vectorization(
//        tokens, embeddingDim, word2VecBC.value), label)
//      }
//
//    (vectorizedRdd.randomSplit(
//      Array(trainingSplit, 1 - trainingSplit)), word2Meta, word2Vec)
//
//  }

  // TODO: Replace SpatialConv and SpatialMaxPolling with 1D implementation
  /**
    * Return a text classification model with the specific num of
    * class
    */
  def buildModel(classNum: Int): Sequential[Float] = {
    val model = Sequential[Float]()

    model.add(Reshape(Array(param.embeddingDim, 1, param.maxSequenceLength)))

    model.add(SpatialConvolution(param.embeddingDim, 128, 5, 1))
    model.add(ReLU())

    model.add(SpatialMaxPooling(5, 1, 5, 1))

    model.add(SpatialConvolution(128, 128, 5, 1))
    model.add(ReLU())

    model.add(SpatialMaxPooling(5, 1, 5, 1))

    model.add(SpatialConvolution(128, 128, 5, 1))
    model.add(ReLU())

    model.add(SpatialMaxPooling(35, 1, 35, 1))

    model.add(Reshape(Array(128)))
    model.add(Linear(128, 100))
    model.add(Linear(100, classNum))
    model.add(LogSoftMax())
    model
  }

  def overwriteCategories(sc: SparkContext, categoriesMapping: Map[String, Int]) = {
    val query = new SQLQuery[Category](classOf[Category], "label > 0")
    sc.grid.clear(query)

    val categories = categoriesMapping.map { case (name, label) => Category(null, name, label) }
    sc.parallelize(categories.toList).saveToGrid()
  }

  /**
    * Start to train the text classification model
    */
  def train(): Unit = {
    val gsConfig = InsightEdgeConfig("insightedge-space", Some("insightedge"), Some("127.0.0.1:4174"))
    val conf = Engine.createSparkConf()
      .setAppName("Text classification")
      .set("spark.task.maxFailures", "1").setInsightEdgeConfig(gsConfig)
    val sc = SparkContext.getOrCreate(conf)
    Engine.init
    val sequenceLen = param.maxSequenceLength
    val embeddingDim = param.embeddingDim //depends on which file is chosen for training. 50d -> 50, 100d -> 100
    val trainingSplit = param.trainingSplit

    val (textToLabel: Seq[(String, Int)], categoriesMapping: Map[String, Int]) = loadRawData()
    overwriteCategories(sc, categoriesMapping)

    val texts = textToLabel.map{ case (text, label) => Text(null, text, label) }
    sc.parallelize(texts).saveToGrid()
    println("Saved text to the grid, count: " + texts.length)

    val textToLabelFloat: Seq[(String, Float)] = textToLabel.map { case (text, label) => (text, label.toFloat) }
    // For large dataset, you might want to get such RDD[(String, Float)] from HDFS
    // String - text, Float - index of category
    val textToLabelRdd: RDD[(String, Float)] = sc.parallelize(textToLabelFloat, param.partitionNum)
    val (word2Meta, word2Vec) = analyzeTexts(textToLabelRdd)
    val word2MetaBC: Broadcast[Map[String, WordMeta]] = sc.broadcast(word2Meta)
    val word2VecBC: Broadcast[Map[Float, Array[Float]]] = sc.broadcast(word2Vec)

    val tokensToLabel: RDD[(Array[Float], Float)] = textToLabelRdd.map { case (text, label) => (toTokens(text, word2MetaBC.value), label) }
    val shapedTokensToLabel: RDD[(Array[Float], Float)] = tokensToLabel.map { case (tokens, label) => (shaping(tokens, sequenceLen), label) }
    val vectorizedRdd: RDD[(Array[Array[Float]], Float)] = shapedTokensToLabel
      .map { case (tokens, label) => (vectorization(tokens, embeddingDim, word2VecBC.value), label) }

    val sampleRDD: RDD[Sample[Float]] = vectorizedRdd.map { case (input: Array[Array[Float]], label: Float) =>
      val flatten: Array[Float] = input.flatten
      val shape: Array[Int] = Array(sequenceLen, embeddingDim)
      val tensor: Tensor[Float] = Tensor(flatten, shape).transpose(1, 2).contiguous()
      Sample(featureTensor = tensor, label = label)
    }

    //    println("Saving sampleRDD to grid")
    //    sampleRDD.map(toIeSample).saveToGrid()
    //    println("Saving sampleRDD to grid: DONE")

    val Array(trainingRDD, validationRDD) = sampleRDD.randomSplit(
      Array(trainingSplit, 1 - trainingSplit))

    val optimizer = Optimizer(
      model = buildModel(classNum),
      sampleRDD = trainingRDD,
      criterion = new ClassNLLCriterion[Float](),
      batchSize = param.batchSize
    )

//    val logdir = "/tmp/bigdl_summaries"
//    val trainSummary = TrainSummary(logdir, "Text classification")
//    val validationSummary = ValidationSummary(logdir, "Text classification")
//    optimizer.setTrainSummary(trainSummary)
//    optimizer.setValidationSummary(validationSummary)

    val trainedModel: Module[Float] = optimizer
      .setOptimMethod(new Adagrad(learningRate = 0.01, learningRateDecay = 0.0002))
      .setValidation(Trigger.everyEpoch, validationRDD, Array(new Top1Accuracy[Float]), param.batchSize)
      .setEndWhen(Trigger.maxEpoch(1))
      .optimize()

    // TODO save to the grid as binary data: @SpaceStorageType(storageType = StorageType.BINARY)
    trainedModel.save("/code/bigdl-fork/data/trained-model/classifier.bigdl", overWrite = true)

    val testData = validationRDD.take(10)
    val testRdd = sc.parallelize(testData)

    val distribution: RDD[Activity] = trainedModel.predict(testRdd)
    println("Distribution prediction")
    for (elem: Activity <- distribution.take(10)) {
      println(elem)
    }

    val labeled: RDD[Int] = trainedModel.predictClass(testRdd)
    println("Labeled prediction")
    for (elem: Int <- labeled.take(10)) {
      println(elem)
    }

    for (elem: Sample[Float] <- testData) {
      println(elem)
    }

    sc.stop()
  }

  def predict(): Unit = {
    val gsConfig = InsightEdgeConfig("insightedge-space", Some("insightedge"), Some("127.0.0.1:4174"))
    val conf = Engine.createSparkConf()
      .setAppName("Text classification")
      .set("spark.task.maxFailures", "1").setInsightEdgeConfig(gsConfig)
    val sc = SparkContext.getOrCreate(conf)
    val categories = sc.gridRdd[Category]().collect()
    println(categories.deep.mkString)

    Engine.init
    val sequenceLen = param.maxSequenceLength
    val embeddingDim = param.embeddingDim //depends on which file is chosen for training. 50d -> 50, 100d -> 100
    val trainingSplit = param.trainingSplit
    val (textToLabel: Seq[(String, Float)], categoriesMapping: Map[String, Int]) = loadRawData()

    // For large dataset, you might want to get such RDD[(String, Float)] from HDFS
    // String - text, Float - index of category
//    val textRdd: RDD[(String, Float)] = sc.parallelize(textToLabel, param.partitionNum)
//    val (word2Meta, word2Vec) = analyzeTexts(textRdd)
//    val word2MetaBC: Broadcast[Map[String, WordMeta]] = sc.broadcast(word2Meta)
//    val word2VecBC: Broadcast[Map[Float, Array[Float]]] = sc.broadcast(word2Vec)
//    val tokensToLabel: RDD[(Array[Float], Float)] = textRdd.map { case (text, label) => (toTokens(text, word2MetaBC.value), label) }
//
//    val shapedTokensToLabel: RDD[(Array[Float], Float)] = tokensToLabel.map { case (tokens, label) => (shaping(tokens, sequenceLen), label) }
//    val vectorizedRdd: RDD[(Array[Array[Float]], Float)] = shapedTokensToLabel
//      .map { case (tokens, label) => (vectorization(tokens, embeddingDim, word2VecBC.value), label) }
//    val sampleRDD: RDD[Sample[Float]] = vectorizedRdd.map { case (input: Array[Array[Float]], label: Float) =>
//      val flatten: Array[Float] = input.flatten
//      val shape: Array[Int] = Array(sequenceLen, embeddingDim)
//      val tensor: Tensor[Float] = Tensor(flatten, shape).transpose(1, 2).contiguous()
//      Sample(featureTensor = tensor, label = label)
//    }
    val textRdd: RDD[String] = sc.parallelize(textToLabel.map(_._1), param.partitionNum)
    val word2Meta = buildWord2Meta(textRdd)
    val word2Vec = buildWord2Vec(word2Meta)
    val word2MetaBC: Broadcast[Map[String, WordMeta]] = sc.broadcast(word2Meta)
    val word2VecBC: Broadcast[Map[Float, Array[Float]]] = sc.broadcast(word2Vec)
    val tokensToLabel: RDD[Array[Float]] = textRdd.map { text => toTokens(text, word2MetaBC.value) }

    val shapedTokensToLabel: RDD[Array[Float]] = tokensToLabel.map { tokens => shaping(tokens, sequenceLen) }
    val vectorizedRdd: RDD[Array[Array[Float]]] = shapedTokensToLabel
      .map { tokens => vectorization(tokens, embeddingDim, word2VecBC.value) }
    val sampleRDD: RDD[Sample[Float]] = vectorizedRdd.map { input: Array[Array[Float]] =>
      val flatten: Array[Float] = input.flatten
      val shape: Array[Int] = Array(sequenceLen, embeddingDim)
      val tensor: Tensor[Float] = Tensor(flatten, shape).transpose(1, 2).contiguous()
      Sample(featureTensor = tensor)
    }

    val Array(trainingRDD, validationRDD: RDD[Sample[Float]]) = sampleRDD.randomSplit(
      Array(trainingSplit, 1 - trainingSplit))

    val trainedModel = Module.load[Float]("/code/bigdl-fork/data/trained-model/classifier.bigdl")

      // TODO fails with null pointer since we don't have labels in tensor - it's OK
//    val methods: Array[ValidationMethod[Float]] = Array(new Top1Accuracy[Float]())
//    val result: Array[(ValidationResult, ValidationMethod[Float])] = trainedModel.evaluate(validationRDD, methods)
//    println(result.deep.mkString("::::"))

    val results = trainedModel.predictClass(validationRDD).take(10)
    println(results.deep.mkString(","))

    sc.stop()
  }

  def predictFromStream(): Unit = {
    val gsConfig = InsightEdgeConfig("insightedge-space", Some("insightedge"), Some("127.0.0.1:4174"))
    val conf = Engine.createSparkConf()
      .setAppName("Text classification")
      .set("spark.task.maxFailures", "1").setInsightEdgeConfig(gsConfig)
    val sc = SparkContext.getOrCreate(conf)
    //    val ssc = new StreamingContext(sc)

    val categories = sc.gridRdd[Category]().collect()
    println(categories.deep.mkString)
    //    ssc.start()
    //    ssc.awaitTermination()

    Engine.init
    val sequenceLen = param.maxSequenceLength
    val embeddingDim = param.embeddingDim //depends on which file is chosen for training. 50d -> 50, 100d -> 100
    val trainingSplit = param.trainingSplit

    val (textToLabel: Seq[(String, Float)], categoriesMapping: Map[String, Int]) = loadRawData()

    val textRdd: RDD[String] = sc.parallelize(textToLabel.map(_._1), param.partitionNum)
    val word2Meta = buildWord2Meta(textRdd)
    val word2Vec = buildWord2Vec(word2Meta)
    val word2MetaBC: Broadcast[Map[String, WordMeta]] = sc.broadcast(word2Meta)
    val word2VecBC: Broadcast[Map[Float, Array[Float]]] = sc.broadcast(word2Vec)
    val tokensToLabel: RDD[Array[Float]] = textRdd.map { text => toTokens(text, word2MetaBC.value) }

    val shapedTokensToLabel: RDD[Array[Float]] = tokensToLabel.map { tokens => shaping(tokens, sequenceLen) }
    val vectorizedRdd: RDD[Array[Array[Float]]] = shapedTokensToLabel
      .map { tokens => vectorization(tokens, embeddingDim, word2VecBC.value) }
    val sampleRDD: RDD[Sample[Float]] = vectorizedRdd.map { input: Array[Array[Float]] =>
      val flatten: Array[Float] = input.flatten
      val shape: Array[Int] = Array(sequenceLen, embeddingDim)
      val tensor: Tensor[Float] = Tensor(flatten, shape).transpose(1, 2).contiguous()
      Sample(featureTensor = tensor)
    }

    val Array(trainingRDD, validationRDD: RDD[Sample[Float]]) = sampleRDD.randomSplit(
      Array(trainingSplit, 1 - trainingSplit))

    val trainedModel = Module.load[Float]("/code/bigdl-fork/data/trained-model/classifier.bigdl")

    // fails with null pointer since we don't have labels in tensor
    //    val methods: Array[ValidationMethod[Float]] = Array(new Top1Accuracy[Float]())
    //    val result: Array[(ValidationResult, ValidationMethod[Float])] = trainedModel.evaluate(validationRDD, methods)
    //    println(result.deep.mkString("::::"))

    val results = trainedModel.predictClass(validationRDD).take(10)
    println(results.deep.mkString(","))

    sc.stop()
  }


  //    def toBigDLSample[T: ClassTag](ieSample: IeSample[T]): Sample[T] = {
  //      Sample(ieSample.data, ieSample.featureSize, ieSample.labelSize)
  //    }

//  def toIeSample(sample: Sample[Float]): IeSample = {
//    IeSample(null, sample.getData(), sample.getFeatureSize(), sample.getLabelSize())
//  }

  /**
    * Train the text classification model with train and val RDDs
    */
  def trainFromData(sc: SparkContext, rdds: Array[RDD[(Array[Array[Float]], Float)]])
  : Module[Float] = {

    // create rdd from input directory
    val trainingRDD = rdds(0).map { case (input: Array[Array[Float]], label: Float) =>
      Sample(
        featureTensor = Tensor(input.flatten, Array(param.maxSequenceLength, param.embeddingDim))
          .transpose(1, 2).contiguous(),
        label = label)
    }

    val valRDD = rdds(1).map { case (input: Array[Array[Float]], label: Float) =>
      Sample(
        featureTensor = Tensor(input.flatten, Array(param.maxSequenceLength, param.embeddingDim))
          .transpose(1, 2).contiguous(),
        label = label)
    }

    // train
    val optimizer = Optimizer(
      model = buildModel(classNum),
      sampleRDD = trainingRDD,
      criterion = new ClassNLLCriterion[Float](),
      batchSize = param.batchSize
    )

    optimizer
      .setOptimMethod(new Adagrad(learningRate = 0.01, learningRateDecay = 0.0002))
      .setValidation(Trigger.everyEpoch, valRDD, Array(new Top1Accuracy[Float]), param.batchSize)
      .setEndWhen(Trigger.maxEpoch(1))
      .optimize()
  }
}


abstract class IeAbstractTextClassificationParams extends Serializable {
  def baseDir: String = "./"

  def maxSequenceLength: Int = 1000

  def maxWordsNum: Int = 20000

  def trainingSplit: Double = 0.8

  def batchSize: Int = 128

  def embeddingDim: Int = Consts.embeddingsDimensions

  def partitionNum: Int = 4
}


/**
  * @param baseDir           The root directory which containing the training and embedding data
  * @param maxSequenceLength number of the tokens
  * @param maxWordsNum       maximum word to be included
  * @param trainingSplit     percentage of the training data
  * @param batchSize         size of the mini-batch
  * @param embeddingDim      size of the embedding vector
  */
case class IeTextClassificationParams(override val baseDir: String = "./",
                                    override val maxSequenceLength: Int = 1000,
                                    override val maxWordsNum: Int = 20000,
                                    override val trainingSplit: Double = 0.8,
                                    override val batchSize: Int = 128,
                                    override val embeddingDim: Int = Consts.embeddingsDimensions,
                                    override val partitionNum: Int = 4)
  extends IeAbstractTextClassificationParams


object Consts {
  val embeddingsDimensions = 50
}