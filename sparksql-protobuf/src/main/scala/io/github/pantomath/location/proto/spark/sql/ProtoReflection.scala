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

package io.github.pantomath.location.proto.spark.sql

import com.google.protobuf.Descriptors.{Descriptor, FieldDescriptor}
import com.google.protobuf.AbstractMessage
import org.apache.spark.sql.types._

import scala.collection.convert.ImplicitConversions.`list asScalaBuffer`

/**
 * Support for generating catalyst schemas for protobuf objects.
 */
object ProtoReflection {
  /** The universe we work in runtime */
  val universe = scala.reflect.runtime.universe

  /** The mirror used to access types in the universe */
  def mirror = universe.runtimeMirror(Thread.currentThread().getContextClassLoader)

  import universe._

  case class Schema(dataType: DataType, nullable: Boolean)

  /** Returns a catalyst DataType and its nullability for the given Scala Type using reflection. */
  def schemaFor[T <: AbstractMessage](clazz: Class[T]): Schema = {
    ScalaReflectionLock.synchronized {
      schemaFor(mirror.classSymbol(clazz).toType)
    }
  }

  /** Returns a catalyst DataType and its nullability for the given Scala Type using reflection. */
  def schemaFor[T: TypeTag]: Schema =
    ScalaReflectionLock.synchronized {
      schemaFor(localTypeOf[T])
    }

  /**
   * Return the Scala Type for `T` in the current classloader mirror.
   *
   * Use this method instead of the convenience method `universe.typeOf`, which
   * assumes that all types can be found in the classloader that loaded scala-reflect classes.
   * That's not necessarily the case when running using Eclipse launchers or even
   * Sbt console or test (without `fork := true`).
   *
   * @see SPARK-5281
   */
  private def localTypeOf[T: TypeTag]: `Type` = typeTag[T].in(mirror).tpe

  /** Returns a catalyst DataType and its nullability for the given Scala Type using reflection. */
  def schemaFor(tpe: `Type`): Schema = ScalaReflectionLock.synchronized {
    tpe match {
      case t if t <:< localTypeOf[AbstractMessage] =>
        val clazz = mirror.runtimeClass(t).asInstanceOf[Class[AbstractMessage]]
        val descriptor = clazz.getMethod("getDescriptor").invoke(null).asInstanceOf[Descriptor]
         Schema(StructType(descriptor.getFields.flatMap(structFieldFor)), nullable = true)
      case other =>
        throw new UnsupportedOperationException(s"Schema for type $other is not supported")
    }
  }

  private def structFieldFor(fd: FieldDescriptor): Option[StructField] = {
    import com.google.protobuf.Descriptors.FieldDescriptor.JavaType._
    val dataType = fd.getJavaType match {
      case INT => Some(IntegerType)
      case LONG => Some(LongType)
      case FLOAT => Some(FloatType)
      case DOUBLE => Some(DoubleType)
      case BOOLEAN => Some(BooleanType)
      case STRING => Some(StringType)
      case BYTE_STRING => Some(BinaryType)
      case ENUM => Some(StringType)
      case MESSAGE =>
        Option(fd.getMessageType.getFields.flatMap(structFieldFor))
          .filter(_.nonEmpty)
          .map(StructType.apply)
    }
    dataType.map( dt => StructField(
      fd.getName,
      if (fd.isRepeated) ArrayType(dt, containsNull = false) else dt,
      nullable = !fd.isRequired && !fd.isRepeated
    ))
  }
}