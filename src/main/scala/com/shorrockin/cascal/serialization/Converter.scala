package com.shorrockin.cascal.serialization

import reflect.Manifest
import annotations.{Keyspace => AKeySpace, Family => AFamily, Super => ASuper, Key => AKey, Value => AValue, SuperColumn => ASuperColumn, Optional}
import com.shorrockin.cascal.model._
import java.lang.annotation.Annotation
import java.util.Arrays
import java.lang.reflect.Field
import com.shorrockin.cascal.utils.{Logging, Conversions}

/**
 * holds a reference to the default converter
 */
object Converter extends Converter(Serializer.Default) with Logging {
}

/**
 *  main class used to convert objects to and from their cascal
 * equivilants.
 *
 * @author Chris Shorrock
 */
class Converter(serializers:Map[Class[_], Serializer[_]]) {

  private var reflectionCache = Map[Class[_], ReflectionInformation]()

//  /**
//   * given a mapped case class returns a list of columns which represent this object
//   */
//  Need to reconsider how best to do this.
//  def apply[T <: AnyRef](caseClass:T):Seq[Column[_]] = {
//    val info = Converter.this.info(caseClass.getClass())
//    var out  = List[Column[_]]()
//
//    val keyField = info.field(classOf[AKey]).getOrElse { throw new IllegalStateException("class does not have key field") }
//    val key = info.family \ keyField._1.get(caseClass)
//
//    val colFields = info.fields(classOf[AValue])
//    val colValues = colFields.map { (tup) => (tup._2.value -> tup._1.get(caseClass)) }
//
//    // if this is super column we need to try to extract the super column
//    // and append columns to that otherwise we can just append them onto
//    // the standard columnsToKey.
//    info.isSuper match {
//      case true  =>
//        val superColField = info.field(classOf[ASuperColumn]).getOrElse { throw new IllegalStateException("class does not have super column field") }
//        val keyValue = keyField.get(caseClass)
//      case false =>
//    }
//
//    out
//  }
  

  /**
   * converts all the column sequences in the provided map (which is returned from a list
   * call). and returns a sequence of the specified type.
   */
  def apply[T](map:Map[SuperColumn, Seq[Column[SuperColumn]]])(implicit manifest:Manifest[T]):Seq[T] = {
    map.keySet.toSeq.map { (key) => apply[T](map(key)) }
  }


  /**
   * given a list of columns, assumed to all belong to the same columnsToKey, creates
   * the object of type T using the annotations present an that class. Uses
   * the serializers to convert values in columns to their appropriate.
   */
  def apply[T](columns:Seq[Column[_]])(implicit manifest:Manifest[T]):T = {
    val info = Converter.this.info(manifest.erasure)

    // iterate over all the types and map them into the values needed to call the
    // constructor to create the object.
    val values:Seq[Any] = info.parameters.map { (paramType) =>
      val cls        = paramType._1
      val annotation = paramType._2

      annotation match {
        // if there's a columnsToKey annotation get the first columnsToKey in the columns and return it
        case k:AKey if (cls.equals(classOf[String])) => columnsToKey(columns).value

        // if there's a super column annotation get the super column then use the serializers
        // to convert the byte array to the appropriate value.
        case sc:ASuperColumn if (info.isSuper) => toObject(cls, columnsToSuperColumn(columns).value)

        // if there's a value annotation look up the column with the matching name and then
        // retrieve the value, and convert it as needed.
        case a:AValue => find(a.value, columns) match {
          case None    => throw new IllegalArgumentException("unable to find column with name: " + a.value)
          case Some(c) => toObject(cls, c.value)
        }

        // optional types are like values except they map to option/some/none so they may or
        // may not exist. additionally - we get the parameter type from the annotation not
        // the actual parameter
        case a:Optional if (cls.equals(classOf[Option[_]])) => find(a.column, columns) match {
          case None    => None
          case Some(c) => Some(toObject(a.as, c.value))
        }

        // anything else throw an exception
        case _ => throw new IllegalStateException("annonation of: " + annotation + " was not placed in such a manner that it could be used")
      }
    }

    info.constructor.newInstance(values.toArray.asInstanceOf[Array[Object]]:_*).asInstanceOf[T]
  }


  /**
   * returns the reflection information from the reflection cache, using
   * DCL to manage access to the cache.
   */
  def info(cls:Class[_]):ReflectionInformation = {
    if (reflectionCache.contains(cls)) reflectionCache(cls)
    else this.synchronized {
      if (reflectionCache.contains(cls)) reflectionCache(cls)
      else {
        val out = ReflectionInformation(cls)
        reflectionCache = reflectionCache + (out.cls.asInstanceOf[Class[_]] -> out)
        out
      }
    }
  }


  /**
   * returns the column with the specified name, or 
   */
  private def find(name:String, columns:Seq[Column[_]]):Option[Column[_]] = {
    val nameBytes = Conversions.bytes(name)
    columns.find { (c) => Arrays.equals(nameBytes, c.name) }
  }


  /**
   * converts the specified byte array to the specified type using the installed
   * serializers.
   */
  private def toObject[A](ofType:Class[A], bytes:Array[Byte]):A = {
    serializers.get(ofType) match {
      case None    => throw new IllegalArgumentException("unable to find serializer for type: " + ofType)
      case Some(s) =>
        // TODO sure there's a better way - without this you end up with:
        //   "value asInstanceOf is not a member of ?"
        val castedSerial = s.asInstanceOf[Serializer[Any]]
        (castedSerial.fromBytes(bytes)).asInstanceOf[A]
    }
  }


  /**
   * returns the common super column that is shared amonst all the columns
   */
  private def columnsToSuperColumn(columns:Seq[Column[_]]):SuperColumn = {
    if (columns.length == 0) throw new IllegalArgumentException("unable to retrieve super column when Seq[Column] is empty")
    columns(0).owner match {
      case sc:SuperColumn => sc
      case _ => throw new IllegalArgumentException("unable to retrieve super column for a standard column")
    }
  }


  /**
   * returns the columnsToKey value for the specified sequence of columns, assumes all columns
   * contain the same columnsToKey.
   */
  private def columnsToKey(columns:Seq[Column[_]]):Key[_, _] = {
    if (columns.length == 0) throw new IllegalArgumentException("unable to retrieve columnsToKey value when Seq[Column] is empty")
    columns(0).key
  }


  /**
   * holds reflection information about a given class
   */
  case class ReflectionInformation(val cls:Class[_]) {
    val keyspace = {
      extract(cls, classOf[AKeySpace]) match {
        case None    => throw new IllegalArgumentException("all mapped classes must contain @Keyspace annotation")
        case Some(v) => Keyspace(v.value())
      }
    }

    val isSuper = {
      extract(cls, classOf[ASuper]) match {
        case None    => false
        case Some(v) => true
      }
    }

    val family = {
      extract(cls, classOf[AFamily]) match {
        case None    => throw new IllegalArgumentException("all mapped classes must contain @Family annotation")
        case Some(f) => isSuper match {
          case true  => SuperColumnFamily(f.value(), keyspace)
          case false => StandardColumnFamily(f.value(), keyspace)
        }
      }
    }

    // TODO examine all - use one with annotations present
    val constructor = cls.getDeclaredConstructors()(0)

    val parameters = {
      val params      = constructor.getParameterTypes
      val annotations = constructor.getParameterAnnotations
      var out         = List[(Class[_], Annotation)]()

      (0 until params.length).foreach { (index) =>
         val annotation = annotations(index)

         if (null == annotation || 0 == annotation.length) {
           throw new IllegalArgumentException("unable to create object when not all parameters have annotations, parameter type: " + params(index) + ", index: " + index)
         }

         if (1 != annotation.length) {
           throw new IllegalArgumentException("in a cascal mapped object each argument must have ONLY one annotation")
         }

         out = (params(index) -> annotation(0)) :: out
      }

      out.reverse
    }

    // map of annotation classes to the field
    val fields = {
      var out = List[(Field, Annotation)]()
      cls.getDeclaredFields.foreach { field =>
        val annotations = field.getDeclaredAnnotations
        if (annotations.length > 0) annotations(0) match {
          case a:AKey         => out = (field -> a) :: out
          case a:Optional     => out = (field -> a) :: out
          case a:ASuperColumn => out = (field -> a) :: out
          case a:AValue       => out = (field -> a) :: out
          case _ => /* ignore */
        }
      }
      out
    }

    /**
     * returns the field for the specified annotation class
     */
    def field[A <: Annotation](cls:Class[A]):Option[(Field, Annotation)] = fields.find { (tup) => cls.equals(tup._2.getClass)}

    /**
     * returns all the fields matching the specified annotation
     */
    def fields[A <: Annotation](cls:Class[A]):Seq[(Field, Annotation)] = fields.filter { (tup) => cls.equals(tup._2.getClass) }
    
    private def extract[A <: Annotation](cls:Class[_], annot:Class[A]):Option[A] = {
      val value = cls.getAnnotation(annot).asInstanceOf[A]
      if (null == value) None
      else Some(value)
    }
  }
}