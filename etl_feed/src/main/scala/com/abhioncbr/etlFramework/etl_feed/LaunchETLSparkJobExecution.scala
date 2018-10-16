package com.abhioncbr.etlFramework.etl_feed

import com.abhioncbr.etlFramework.commons.Context
import com.abhioncbr.etlFramework.commons.ContextConstantEnum._
import com.abhioncbr.etlFramework.commons.extract.{Extract, ExtractionType}
import com.abhioncbr.etlFramework.commons.job.JobStaticParam
import com.abhioncbr.etlFramework.commons.load.{Load, LoadType}
import com.abhioncbr.etlFramework.commons.transform.{Transform, TransformationResult}
import com.abhioncbr.etlFramework.etl_feed.extractData.{ExtractDataFromDB, ExtractDataFromHive, ExtractDataFromJson}
import com.abhioncbr.etlFramework.etl_feed.loadData.{LoadDataIntoFileSystem, LoadDataIntoHive}
import com.google.common.base.Objects
import com.abhioncbr.etlFramework.etl_feed.transformData.TransformData
import com.abhioncbr.etlFramework.etl_feed_metrics.stats.UpdateFeedStats
import com.abhioncbr.etlFramework.job_conf.xml.ParseETLJobXml
import org.apache.hadoop.conf.Configuration
import com.abhioncbr.etlFramework.etl_feed_metrics.stats.JobResult
import com.abhioncbr.etlFramework.commons.Logger
import com.abhioncbr.etlFramework.etl_feed.validateData.ValidateTransformedData
import org.apache.spark.sql.{DataFrame, SQLContext, SparkSession}
import org.apache.spark.SparkContext
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.xml.XML

class LaunchETLSparkJobExecution(feedName: String ,firstDate: Option[DateTime], secondDate: Option[DateTime], xmlInputFilePath: String){
  def configureFeedJob: Either[Boolean, String] ={
    Context.addContextualObject[Option[DateTime]](FIRST_DATE, firstDate)
    Context.addContextualObject[Option[DateTime]](SECOND_DATE, secondDate)

    val FALSE = false
    val parse = new ParseETLJobXml
    parse.parseXml(xmlInputFilePath, FALSE) match {
      case Left(xmlContent) => parse.parseNode(XML.loadString(xmlContent)) match {
        case Left(tuple) =>
          Context.addContextualObject[JobStaticParam] (JOB_STATIC_PARAM, tuple._1)
          Context.addContextualObject[Extract] (EXTRACT, tuple._2)
          Context.addContextualObject[Transform] (TRANSFORM, tuple._3)
          Context.addContextualObject[Load] (LOAD, tuple._4)
        case Right(parseError) => Logger.log.error(parseError)
          return Right(parseError)
      }
      case Right(xmlLoadError) => Logger.log.error(xmlLoadError)
        return Right(xmlLoadError)
    }

    val appName = s"etl-$feedName"
    val sparkSession: SparkSession = SparkSession.builder().appName(appName).getOrCreate()
    Context.addContextualObject[Configuration](HADOOP_CONF, new Configuration)
    Context.addContextualObject[SparkContext](SPARK_CONTEXT, sparkSession.sparkContext)
    Context.addContextualObject[SQLContext](SQL_CONTEXT, sparkSession.sqlContext)

    Left(true)
  }

  def executeFeedJob: Either[Array[JobResult], String]={
    val extractionResult = extract
    if(extractionResult.isRight) return Right(extractionResult.right.get)
    Logger.log.info("Extraction phase of the feed is completed")

    //TODO: validate extracted data based on condition & boolean operator.

    val transformedResult = transformation(extractionResult.left.get)
    if(transformedResult.isRight) return Right(transformedResult.right.get)
    Logger.log.info("Transformation phase of the feed is completed")

    // validating transformed data, if it is configured to be validated.
    val validateTransformedData: Boolean  = Context.getContextualObject[Transform](TRANSFORM).validateTransformedData
    val validateResult: Either[Array[(DataFrame, DataFrame, Any, Any)], String]  = if(validateTransformedData) {
      validate(transformedResult.left.get)
    } else {
      Left(transformedResult.left.get.map(array => (array.resultDF, null, array.resultInfo1, array.resultInfo1)))
    }
    if(validateResult.isRight) return Right(validateResult.right.get)
    if(validateTransformedData) Logger.log.info("Validation phase of the feed is completed")

    // loading the transformed data.
    val loadResult = load(validateResult.left.get)
    if(loadResult.isRight) return Right(validateResult.right.get)
    Logger.log.info ("Load phase of the feed is completed")

    Left(loadResult.left.get)
  }

  private def extract: Either[DataFrame, String] = {
    val extractionType = Context.getContextualObject[Extract](EXTRACT).extractionType
    extractionType match {
      case ExtractionType.JSON => Left((new ExtractDataFromJson).getRawData)
      case ExtractionType.JDBC => Left((new ExtractDataFromDB).getRawData)
      case ExtractionType.HIVE => Left((new ExtractDataFromHive).getRawData)
      case _ => Right(s"extracting data from $extractionType is not supported right now.")
    }
  }

  private def transformation(extractionDF: DataFrame): Either[Array[TransformationResult], String] = {
    //testing whether extracted data frame is having data or not. If not then, error message is returned.
    if(extractionDF.first == null) return Right("Extracted data frame contains no data row")

    new TransformData(Context.getContextualObject[Transform](TRANSFORM)).performTransformation(extractionDF)
  }

  private def validate(transformationDF: Array[TransformationResult]): Either[Array[(DataFrame, DataFrame, Any, Any)], String] = {
    //testing whether transformed data frames have data or not.
    transformationDF.map(res => res.resultDF).foreach(df => if(df.first == null ) return Right("Transformed data frame contains no data row"))

    var output: Array[ (DataFrame, DataFrame, Any, Any) ] = Array()
    transformationDF.foreach( arrayElement =>  {
      val validator = new ValidateTransformedData
      val validateSchemaResult = validator.validateSchema (arrayElement.resultDF)
      if(validateSchemaResult._1) {
        output = output ++ validator.validateData(arrayElement.resultDF, validateSchemaResult._2.get, arrayElement.resultInfo1, arrayElement.resultInfo2)
      } else { validateSchemaResult._2 match {
          case Some(_2) => Logger.log.error("hive table schema & data frame schema does not match. Below are schemas for reference -")
            Logger.log.error(s"table schema:: ${_2.mkString}")
            Logger.log.error(s"data frame schema:: ${validateSchemaResult._3.get.mkString}")
          case None => Logger.log.error(s"provided hive table does not exist.")}
          return Right("Validation failed. Please check the log.")
      }
    })
    Left(output)
  }

  private def load(validationArrayDF: Array[ (DataFrame, DataFrame, Any, Any) ]): Either[Array[JobResult], String] = {
    //testing whether validated data frames have data or not.
    validationArrayDF.foreach(tuple => {
      if (tuple._1.first == null) {
        println(s"validate failed"); return Right("Transformed data frame contains no data row")
      }
    })

    val FALSE = false
    //TODO: load tables for multiple data frames
    val loadResult: Array[JobResult] = validationArrayDF.map( validate => {
      val loadType = Context.getContextualObject[Load](LOAD).loadType
      val loadResult: Either[Boolean, String] = loadType match {
        case LoadType.HIVE => (new LoadDataIntoHive).loadTransformedData(validate._1)
        case LoadType.FILE_SYSTEM => (new LoadDataIntoFileSystem).loadTransformedData(validate._1)
        case LoadType.JDBC => Right(s"loading data to $loadType is not supported right now.")
        case _ => Right(s"loading data to $loadType is not supported right now.")
      }
      //writing output data tuple.
      (loadResult, validate._1.count(), if(validate._2 != null ) validate._2.count() else 0, validate._3, validate._4)
    }).map(result => {
      if(result._1.isRight) JobResult(FALSE, "", result._4.asInstanceOf[Int], result._5.asInstanceOf[Int], result._2, result._3, result._1.right.get)
      else JobResult(result._1.left.get, "", result._4.asInstanceOf[Int], result._5.asInstanceOf[Int], result._2, result._3, "")})
    Left(loadResult)
  }


}

object LaunchETLSparkJobExecution extends App{

  def launch(args: Array[String]): Unit = {
    case class CommandOptions(firstDate: Option[DateTime] = None, feedName : String = "",
                              secondDate: Option[DateTime] = None, xmlInputFilePath: String = "", statScriptFilePath: String = "") {
      override def toString: String =
        Objects.toStringHelper(this)
          .add("feed_name", feedName)
          .add("firstDate", firstDate)
          .add("secondDate", secondDate)
          .add("xmlFilePath", xmlInputFilePath)
          .add("statScriptFilePath", statScriptFilePath)
          .toString
    }
    val DatePattern = "yyyy-MM-dd HH:mm:ss"
    val dateParser  = DateTimeFormat.forPattern(DatePattern)

    val parser = new scopt.OptionParser[CommandOptions]("") {
      opt[String]('e', "etl_feed_name")
        .action((e, f) => f.copy(feedName = e))
        .text("required Etl feed name.")
        .required

      opt[String]('d', "date")
        .action((fd, c) =>  c.copy(firstDate = if(!fd.trim.isEmpty) Some(dateParser.parseDateTime(fd)) else None))
        .text("required for all jobs except of hourly jobs or once execution jobs.")
        .optional

      opt[String]('s', "second_date")
        .action((sd, c) =>  c.copy(secondDate = if(!sd.trim.isEmpty) Some(dateParser.parseDateTime(sd)) else None))
        .text("required only in case of date range jobs")
        .optional

      opt[String]('x', "xml_file_path")
        .action((xfp, c) => c.copy(xmlInputFilePath = xfp))
        .text("required xml file path for etl job execution steps.")
        .required

      opt[String]('f', "stat_script_file_path")
        .action((sfp, c) => c.copy(statScriptFilePath = sfp))
        .text("required stat script file path for etl job result updates.")
        .optional

    }

    parser.parse(args, CommandOptions()) match {
      case Some(opts) =>
        Logger.log.info(s"Going to start the execution of the etl feed job: ")
        var exitCode = -1
        val etlExecutor = new LaunchETLSparkJobExecution(opts.feedName, opts.firstDate, opts.secondDate, opts.xmlInputFilePath)

        var metricData = 0L
        val updateFeedStats: UpdateFeedStats = new UpdateFeedStats(opts.feedName, opts.firstDate.getOrElse(DateTime.now))
        etlExecutor.configureFeedJob match {
          case Left(b) => val start = System.currentTimeMillis()
            val feedJobOutput = etlExecutor.executeFeedJob
            val end = System.currentTimeMillis()
            feedJobOutput match {
              case Left(dataArray) => /*val temp = dataArray.map(data =>
                updateFeedStats.updateFeedStat(opts.statScriptFilePath, data.subtask, data.validateCount,
                  data.nonValidatedCount, end - start, "success", data.transformationPassedCount,
                  data.transformationFailedCount, data.failureReason)).map(status => if(status==0) true else false)
                .reduce(_ && _)
                if(temp) exitCode = 0
                metricData = dataArray.head.validateCount*/
                println("job complete.")
              //else
              case Right(s) => updateFeedStats.updateFeedStat(opts.statScriptFilePath, "", 0, 0, end - start, "fail", 0, 0, s)
                Logger.log.error(s)
            }
          case Right(s) => updateFeedStats.updateFeedStat(opts.statScriptFilePath, "", 0, 0, 0, "fail", 0, 0, s)
            Logger.log.error(s)
        }

        //TODO: Promethus push metrics, commented in refactoring [will be triggered based on job static param]
        //pushMetrics(opts.feedName)

        Logger.log.info(s"Etl job finish with exit code: $exitCode")
        System.exit(exitCode)
      case None =>
        parser.showTryHelp()
    }
  }

  launch(args)
}