package shark.execution

import java.beans.{XMLDecoder, XMLEncoder, PersistenceDelegate}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectOutput, ObjectInput}
import java.nio.ByteBuffer

import com.ning.compress.lzf.{LZFEncoder, LZFDecoder}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hive.ql.exec.Utilities.EnumDelegate
import org.apache.hadoop.hive.ql.plan.GroupByDesc
import org.apache.hadoop.hive.ql.plan.PlanUtils.ExpressionTypes

import shark.{SharkConfVars, LogHelper}



/**
 * A wrapper around our operators so they can be serialized by standard Java
 * serialization. This really just delegates the serialization of the operators
 * to XML, and that of object inspectors to Kryo.
 *
 * Use OperatorSerializationWrapper(operator) to create a wrapper.
 */
class OperatorSerializationWrapper[T <: Operator[_ <: HiveOperator]]
  extends Serializable with shark.LogHelper {

  /** The operator we are going to serialize. */
  @transient var _value: T = _

  /** The operator serialized by the XMLEncoder, minus the object inspectors. */
  var opSerialized: Array[Byte] = _

  /** The object inspectors, serialized by Kryo. */
  var objectInspectorsSerialized: Array[Byte] = _

  def value: T = {
    if (_value == null) {
      assert(opSerialized != null)
      assert(opSerialized.length > 0)
      assert(objectInspectorsSerialized != null)
      assert(objectInspectorsSerialized.length > 0)
      _value = XmlSerializer.deserialize[T](opSerialized)
      _value.objectInspectors = KryoSerializer.deserialize(objectInspectorsSerialized)
    }
    _value
  }

  def value_= (v: T):Unit = {
    _value = v
    opSerialized = XmlSerializer.serialize(value, v.hconf)
    objectInspectorsSerialized = KryoSerializer.serialize(value.objectInspectors)
  }

  override def toString(): String = {
    if (value != null) {
      "OperatorSerializationWrapper[ " + value.toString() + " ]"
    } else {
      super.toString()
    }
  }
}


object OperatorSerializationWrapper {
  def apply[T <: Operator[_ <: HiveOperator]](value: T): OperatorSerializationWrapper[T] = {
    val wrapper = new OperatorSerializationWrapper[T]
    wrapper.value = value
    wrapper
  }
}


/**
 * Java object serialization using XML encoder/decoder. Avoid using this to
 * serialize byte arrays because it is extremely inefficient.
 */
object XmlSerializer {
  // We prepend the buffer with a byte indicating whether payload is compressed
  val COMPRESSION_ENABLED : Byte = 1;
  val COMPRESSION_DISABLED : Byte = 0;

  def serialize[T](o: T, conf: Configuration): Array[Byte] = {
    val byteStream = new ByteArrayOutputStream()
    val e = new XMLEncoder(byteStream)
    // workaround for java 1.5
    e.setPersistenceDelegate(classOf[ExpressionTypes], new EnumDelegate())
    e.setPersistenceDelegate(classOf[GroupByDesc.Mode], new EnumDelegate())
    e.writeObject(o)
    e.close()

    val useCompression = conf match {
      case null => SharkConfVars.COMPRESS_QUERY_PLAN.defaultBoolVal
      case _ => SharkConfVars.getBoolVar(conf, SharkConfVars.COMPRESS_QUERY_PLAN)
    }

    if (useCompression) {
      COMPRESSION_ENABLED +: LZFEncoder.encode(byteStream.toByteArray())
    } else {
      COMPRESSION_DISABLED +: byteStream.toByteArray
    }
  }

  def deserialize[T](bytes: Array[Byte]): T  = {
    val cl = Thread.currentThread.getContextClassLoader
    val decodedStream =
      if (bytes(0) == COMPRESSION_ENABLED) {
        new ByteArrayInputStream(LZFDecoder.decode(bytes.slice(1, bytes.size)))
      } else {
        new ByteArrayInputStream(bytes.slice(1, bytes.size))
      }
    val d: XMLDecoder = new XMLDecoder(decodedStream, null, null, cl)
    val ret = d.readObject()
    d.close()
    ret.asInstanceOf[T]
  }
}


/**
 * Java object serialization using Kryo. This is much more efficient, but Kryo
 * sometimes is buggy to use. We use this mainly to serialize the object
 * inspectors.
 */
object KryoSerializer {

  @transient val ser = new spark.KryoSerializer

  def serialize[T](o: T): Array[Byte] = {
    ser.newInstance().serialize(o).array()
  }

  def deserialize[T](bytes: Array[Byte]): T  = {
    ser.newInstance().deserialize[T](ByteBuffer.wrap(bytes))
  }
}


object JavaSerializer {
  @transient val ser = new spark.JavaSerializer

  def serialize[T](o: T): Array[Byte] = {
    ser.newInstance().serialize(o).array()
  }

  def deserialize[T](bytes: Array[Byte]): T  = {
    ser.newInstance().deserialize[T](ByteBuffer.wrap(bytes))
  }
}


/**
 * A wrapper around some unserializable objects that make them both Java
 * serializable. Internally, Kryo is used for serialization.
 *
 * Use KryoSerializationWrapper(value) to create a wrapper.
 */
class KryoSerializationWrapper[T] extends Serializable {

  @transient var value: T = _

  private var valueSerialized: Array[Byte] = _

  // The getter and setter for valueSerialized is used for XML serialization.
  def getValueSerialized(): Array[Byte] = {
    valueSerialized = KryoSerializer.serialize(value)
    valueSerialized
  }

  def setValueSerialized(bytes: Array[Byte]) = {
    valueSerialized = bytes
    value = KryoSerializer.deserialize[T](valueSerialized)
  }

  // Used for Java serialization.
  private def writeObject(out: java.io.ObjectOutputStream) {
    getValueSerialized()
    out.defaultWriteObject()
  }

  private def readObject(in: java.io.ObjectInputStream) {
    in.defaultReadObject()
    setValueSerialized(valueSerialized)
  }
}


object KryoSerializationWrapper {
  def apply[T](value: T): KryoSerializationWrapper[T] = {
    val wrapper = new KryoSerializationWrapper[T]
    wrapper.value = value
    wrapper
  }
}

