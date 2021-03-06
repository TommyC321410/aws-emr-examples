package com.datapyro.emr.integration

import java.util
import java.util.Properties

import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.datapyro.emr.common.EmrConstants
import com.datapyro.emr.common.Utils.md5
import com.datapyro.emr.dynamo.SparkCSVToDynamoDB.dynamoTableName
import org.apache.hadoop.dynamodb.DynamoDBItemWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.api.java.function.PairFunction
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.{Row, SparkSession}

/**
  * This example reads data from a jdbc source and writes to a DynamoDB table
  *
  */
object SparkJDBCToDynamoDB extends App {

  // check args
  if (args.length != 6) {
    println("Invalid usage! You should provide jdbc url, user, password, table and DynamoDB table name")
    System.exit(-1)
  }
  val url = args(0)
  val user = args(1)
  val pass = args(2)
  val table = args(3)
  val tableName = args(4)

  // initialize context
  val sparkMaster: Option[String] = Option(System.getProperty("spark.master"))

  val spark = SparkSession.builder
    .master(sparkMaster.getOrElse("yarn"))
    .appName(getClass.getSimpleName)
    .getOrCreate()

  import spark.implicits._

  // read from database
  val properties: Properties = new Properties
  properties.setProperty("user", user)
  properties.setProperty("password", pass)

  // add unique key column
  def getUniqueId() = udf((stockSymbol: String, date: String) => md5(stockSymbol + date))

  val df = spark.read
      .jdbc(url, table, properties)
      .withColumn("id", getUniqueId()($"stock_symbol", $"date"))

  // write to dynamodb
  val region = EmrConstants.REGION
  val endpoint = s"dynamodb.$region.amazonaws.com"

  val jobConf = new JobConf(spark.sparkContext.hadoopConfiguration)
  jobConf.set("dynamodb.output.tableName", dynamoTableName)
  jobConf.set("dynamodb.endpoint", endpoint)
  jobConf.set("dynamodb.regionid", region)
  jobConf.set("dynamodb.servicename", "dynamodb")
  jobConf.set("dynamodb.throughput.write", "1")
  jobConf.set("dynamodb.throughput.write.percent", "1")
  jobConf.set("mapred.input.format.class", "org.apache.hadoop.dynamodb.read.DynamoDBInputFormat")
  jobConf.set("mapred.output.format.class", "org.apache.hadoop.dynamodb.write.DynamoDBOutputFormat")

  // save
  val columns = df.columns
  df.javaRDD
    .mapToPair(new PairFunction[Row, Text, DynamoDBItemWritable]() {
      @throws[Exception]
      override def call(row: Row): (Text, DynamoDBItemWritable) = {
        return (new Text(""), createItem(row, columns))
      }
    })
    .saveAsHadoopDataset(jobConf)

  // functions

  def createItem(row: Row, columns: Array[String]): DynamoDBItemWritable = {
    val attributes: util.Map[String, AttributeValue] = new util.HashMap[String, AttributeValue]
    // add fields as attributes
    for (column <- columns) {
      val value: Any = row.get(row.fieldIndex(column))
      if (value != null) {
        val attributeValue: AttributeValue = new AttributeValue
        if (value.isInstanceOf[String] || value.isInstanceOf[Boolean]) attributeValue.setS(value.toString)
        else attributeValue.setN(value.toString)
        attributes.put(column, attributeValue)
      }
    }
    // create row
    val item: DynamoDBItemWritable = new DynamoDBItemWritable
    item.setItem(attributes)
    item
  }

}
