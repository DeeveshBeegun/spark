/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.protobuf

import com.google.protobuf.{ByteString, DynamicMessage, Message}

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.{RandomDataGenerator, Row}
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow, NoopFilters, OrderedFilters, StructFilters}
import org.apache.spark.sql.catalyst.expressions.{ExpressionEvalHelper, GenericInternalRow, Literal}
import org.apache.spark.sql.catalyst.util.{ArrayBasedMapData, GenericArrayData, MapData}
import org.apache.spark.sql.protobuf.protos.CatalystTypes.BytesMsg
import org.apache.spark.sql.protobuf.utils.{ProtobufUtils, SchemaConverters}
import org.apache.spark.sql.sources.{EqualTo, Not}
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

class ProtobufCatalystDataConversionSuite
    extends SparkFunSuite
    with SharedSparkSession
    with ExpressionEvalHelper {

  private val testFileDesc = testFile("catalyst_types.desc").replace("file:/", "/")
  private val javaClassNamePrefix = "org.apache.spark.sql.protobuf.protos.CatalystTypes$"

  private def checkResultWithEval(
      data: Literal,
      descFilePath: String,
      messageName: String,
      expected: Any): Unit = {

    withClue("(Eval check with Java class name)") {
      val className = s"$javaClassNamePrefix$messageName"
      checkEvaluation(
        ProtobufDataToCatalyst(
          CatalystDataToProtobuf(data, className),
          className,
          descFilePath = None),
        prepareExpectedResult(expected))
    }
    withClue("(Eval check with descriptor file)") {
      checkEvaluation(
        ProtobufDataToCatalyst(
          CatalystDataToProtobuf(data, messageName, Some(descFilePath)),
          messageName,
          descFilePath = Some(descFilePath)),
        prepareExpectedResult(expected))
    }
  }

  protected def checkUnsupportedRead(
      data: Literal,
      descFilePath: String,
      actualSchema: String,
      badSchema: String): Unit = {

    val binary = CatalystDataToProtobuf(data, actualSchema, Some(descFilePath))

    intercept[Exception] {
      ProtobufDataToCatalyst(binary, badSchema, Some(descFilePath), Map("mode" -> "FAILFAST"))
        .eval()
    }

    val expected = {
      val expectedSchema = ProtobufUtils.buildDescriptor(descFilePath, badSchema)
      SchemaConverters.toSqlType(expectedSchema).dataType match {
        case st: StructType =>
          Row.fromSeq((0 until st.length).map { _ =>
            null
          })
        case _ => null
      }
    }

    checkEvaluation(
      ProtobufDataToCatalyst(binary, badSchema, Some(descFilePath), Map("mode" -> "PERMISSIVE")),
      expected)
  }

  protected def prepareExpectedResult(expected: Any): Any = expected match {
    // Spark byte and short both map to Protobuf int
    case b: Byte => b.toInt
    case s: Short => s.toInt
    case row: GenericInternalRow => InternalRow.fromSeq(row.values.map(prepareExpectedResult))
    case array: GenericArrayData => new GenericArrayData(array.array.map(prepareExpectedResult))
    case map: MapData =>
      val keys = new GenericArrayData(
        map.keyArray().asInstanceOf[GenericArrayData].array.map(prepareExpectedResult))
      val values = new GenericArrayData(
        map.valueArray().asInstanceOf[GenericArrayData].array.map(prepareExpectedResult))
      new ArrayBasedMapData(keys, values)
    case other => other
  }

  private val testingTypes = Seq(
    StructType(StructField("int32_type", IntegerType, nullable = true) :: Nil),
    StructType(StructField("double_type", DoubleType, nullable = true) :: Nil),
    StructType(StructField("float_type", FloatType, nullable = true) :: Nil),
    StructType(StructField("bytes_type", BinaryType, nullable = true) :: Nil),
    StructType(StructField("string_type", StringType, nullable = true) :: Nil))

  private val catalystTypesToProtoMessages: Map[DataType, (String, Any)] = Map(
    IntegerType -> ("IntegerMsg", 0),
    DoubleType -> ("DoubleMsg", 0.0d),
    FloatType -> ("FloatMsg", 0.0f),
    BinaryType -> ("BytesMsg", ByteString.empty().toByteArray),
    StringType -> ("StringMsg", ""))

  testingTypes.foreach { dt =>
    val seed = 1 + scala.util.Random.nextInt((1024 - 1) + 1)
    test(s"single $dt with seed $seed") {

      val (messageName, defaultValue) = catalystTypesToProtoMessages(dt.fields(0).dataType)

      val rand = new scala.util.Random(seed)
      val generator = RandomDataGenerator.forType(dt, rand = rand).get
      var data = generator()
      while (data.asInstanceOf[Row].get(0) == defaultValue) // Do not use default values, since
        data = generator()                                  // from_protobuf() returns null in v3.

      val converter = CatalystTypeConverters.createToCatalystConverter(dt)
      val input = Literal.create(converter(data), dt)

      checkResultWithEval(
        input,
        testFileDesc,
        messageName,
        input.eval())
    }
  }

  private def checkDeserialization(
      descFilePath: String,
      messageName: String,
      data: Message,
      expected: Option[Any],
      filters: StructFilters = new NoopFilters): Unit = {

    val descriptor = ProtobufUtils.buildDescriptor(descFilePath, messageName)
    val dataType = SchemaConverters.toSqlType(descriptor).dataType

    val deserializer = new ProtobufDeserializer(descriptor, dataType, filters)

    val dynMsg = DynamicMessage.parseFrom(descriptor, data.toByteArray)
    val deserialized = deserializer.deserialize(dynMsg)

    // Verify Java class deserializer matches with descriptor based serializer.
    val javaDescriptor = ProtobufUtils
      .buildDescriptorFromJavaClass(s"$javaClassNamePrefix$messageName")
    assert(dataType == SchemaConverters.toSqlType(javaDescriptor).dataType)
    val javaDeserialized = new ProtobufDeserializer(javaDescriptor, dataType, filters)
      .deserialize(DynamicMessage.parseFrom(javaDescriptor, data.toByteArray))
    assert(deserialized == javaDeserialized)

    expected match {
      case None => assert(deserialized.isEmpty)
      case Some(d) =>
        assert(checkResult(d, deserialized.get, dataType, exprNullable = false))
    }
  }

  test("Handle unsupported input of message type") {
    val actualSchema = StructType(
      Seq(
        StructField("col_0", StringType, nullable = false),
        StructField("col_1", IntegerType, nullable = false),
        StructField("col_2", FloatType, nullable = false),
        StructField("col_3", BooleanType, nullable = false),
        StructField("col_4", DoubleType, nullable = false)))

    val seed = scala.util.Random.nextLong()
    withClue(s"create random record with seed $seed") {
      val data = RandomDataGenerator.randomRow(new scala.util.Random(seed), actualSchema)
      val converter = CatalystTypeConverters.createToCatalystConverter(actualSchema)
      val input = Literal.create(converter(data), actualSchema)
      checkUnsupportedRead(input, testFileDesc, "Actual", "Bad")
    }
  }

  test("filter push-down to Protobuf deserializer") {

    val sqlSchema = new StructType()
      .add("name", "string")
      .add("age", "int")

    val descriptor = ProtobufUtils.buildDescriptor(testFileDesc, "Person")
    val dynamicMessage = DynamicMessage
      .newBuilder(descriptor)
      .setField(descriptor.findFieldByName("name"), "Maxim")
      .setField(descriptor.findFieldByName("age"), 39)
      .build()

    val expectedRow = Some(InternalRow(UTF8String.fromString("Maxim"), 39))
    checkDeserialization(testFileDesc, "Person", dynamicMessage, expectedRow)
    checkDeserialization(
      testFileDesc,
      "Person",
      dynamicMessage,
      expectedRow,
      new OrderedFilters(Seq(EqualTo("age", 39)), sqlSchema))

    checkDeserialization(
      testFileDesc,
      "Person",
      dynamicMessage,
      None,
      new OrderedFilters(Seq(Not(EqualTo("name", "Maxim"))), sqlSchema))
  }

  test("ProtobufDeserializer with binary type") {

    val bb = java.nio.ByteBuffer.wrap(Array[Byte](97, 48, 53))

    val bytesProto = BytesMsg
      .newBuilder()
      .setBytesType(ByteString.copyFrom(bb))
      .build()

    val expected = InternalRow(Array[Byte](97, 48, 53))
    checkDeserialization(testFileDesc, "BytesMsg", bytesProto, Some(expected))
  }

  test("Full names for message using descriptor file") {
    val withShortName = ProtobufUtils.buildDescriptor(testFileDesc, "BytesMsg")
    assert(withShortName.findFieldByName("bytes_type") != null)

    val withFullName = ProtobufUtils.buildDescriptor(
      testFileDesc, "org.apache.spark.sql.protobuf.BytesMsg")
    assert(withFullName.findFieldByName("bytes_type") != null)
  }
}
