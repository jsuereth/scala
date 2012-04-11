package scala.reflect

/** An `ArrayTag[T]` is a descriptor that is requested by the compiler every time
 *  when an array is instantiated, but the element type is unknown at compile time.
 *
 *  Scala library provides a standard implementation of this trait,
 *  `ClassTag[T]` that explicitly carries the `java.lang.Class` erasure of type T.
 *
 *  However other platforms (e.g. a Scala -> JS crosscompiler) may reimplement this trait as they see fit
 *  and then expose the implementation via an implicit macro.
 */
@annotation.implicitNotFound(msg = "No ArrayTag available for ${T}")
trait ArrayTag[T] {
  /** Produces an `ArrayTag` that knows how to build `Array[Array[T]]` */
  def wrap: ArrayTag[Array[T]]

  /** Produces a new array with element type `T` and length `len` */
  def newArray(len: Int): Array[T]
}