/**
  * Copyright 2014 Dropbox, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package djinni

import djinni.ast.Record.DerivingType
import djinni.ast._
import djinni.generatorTools._
import djinni.meta._
import djinni.writer.IndentWriter

import scala.collection.mutable

class JavaGenerator(spec: Spec) extends Generator(spec) {

  val javaAnnotationHeader = spec.javaAnnotation.map(pkg => '@' + pkg.split("\\.").last)
  val javaNullableAnnotation = spec.javaNullableAnnotation.map(pkg => '@' + pkg.split("\\.").last)
  val javaNonnullAnnotation = spec.javaNonnullAnnotation.map(pkg => '@' + pkg.split("\\.").last)
  val marshal = new JavaMarshal(spec)

  class JavaRefs() {
    var java = mutable.TreeSet[String]()

    spec.javaAnnotation.foreach(pkg => java.add(pkg))
    spec.javaNullableAnnotation.foreach(pkg => java.add(pkg))
    spec.javaNonnullAnnotation.foreach(pkg => java.add(pkg))

    def find(ty: TypeRef) { find(ty.resolved) }
    def find(tm: MExpr) {
      tm.args.foreach(find)
      find(tm.base)
    }
    def find(m: Meta) = for(r <- marshal.references(m)) r match {
      case ImportRef(arg) => java.add(arg)
      case _ =>
    }
  }

  def writeJavaFile(ident: String, origin: String, refs: Iterable[String], f: IndentWriter => Unit) {
    createFile(spec.javaOutFolder.get, idJava.ty(ident) + ".java", (w: IndentWriter) => {
      w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
      w.wl("// This file generated by Djinni from " + origin)
      w.wl
      spec.javaPackage.foreach(s => w.wl(s"package $s;").wl)
      if (refs.nonEmpty) {
        refs.foreach(s => w.wl(s"import $s;"))
        w.wl
      }
      f(w)
    })
  }

  def generateJavaConstants(w: IndentWriter, consts: Seq[Const]) = {

    def writeJavaConst(w: IndentWriter, ty: TypeRef, v: Any): Unit = v match {
      case l: Long => w.w(l.toString)
      case d: Double if marshal.fieldType(ty) == "float" => w.w(d.toString + "f")
      case d: Double => w.w(d.toString)
      case b: Boolean => w.w(if (b) "true" else "false")
      case s: String => w.w(s)
      case e: EnumValue =>  w.w(s"${marshal.typename(ty)}.${idJava.enum(e)}")
      case v: ConstRef => w.w(idJava.const(v))
      case z: Map[_, _] => { // Value is record
        val recordMdef = ty.resolved.base.asInstanceOf[MDef]
        val record = recordMdef.body.asInstanceOf[Record]
        val vMap = z.asInstanceOf[Map[String, Any]]
        w.wl(s"new ${marshal.typename(ty)}(")
        w.increase()
        // Use exact sequence
        val skipFirst = SkipFirst()
        for (f <- record.fields) {
          skipFirst {w.wl(",")}
          writeJavaConst(w, f.ty, vMap.apply(f.ident.name))
          w.w(" /* " + idJava.field(f.ident) + " */ ")
        }
        w.w(")")
        w.decrease()
      }
    }

    for (c <- consts) {
      writeDoc(w, c.doc)
      javaAnnotationHeader.foreach(w.wl)
      marshal.nullityAnnotation(c.ty).foreach(w.wl)
      w.w(s"public static final ${marshal.fieldType(c.ty)} ${idJava.const(c.ident)} = ")
      writeJavaConst(w, c.ty, c.value)
      w.wl(";")
      w.wl
    }
  }

  override def generateEnum(origin: String, ident: Ident, doc: Doc, e: Enum) {
    val refs = new JavaRefs()

    writeJavaFile(ident, origin, refs.java, w => {
      writeDoc(w, doc)
      javaAnnotationHeader.foreach(w.wl)
      w.w(s"public enum ${marshal.typename(ident, e)}").braced {
        for (o <- e.options) {
          writeDoc(w, o.doc)
          w.wl(idJava.enum(o.ident) + ",")
        }
        w.wl(";")
      }
    })
  }

  override def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface) {
    val refs = new JavaRefs()

    i.methods.map(m => {
      m.params.map(p => refs.find(p.ty))
      m.ret.foreach(refs.find)
    })
    i.consts.map(c => {
      refs.find(c.ty)
    })
    if (i.ext.cpp) {
      refs.java.add("java.util.concurrent.atomic.AtomicBoolean")
    }

    writeJavaFile(ident, origin, refs.java, w => {
      val javaClass = marshal.typename(ident, i)
      val typeParamList = javaTypeParams(typeParams)
      writeDoc(w, doc)

      javaAnnotationHeader.foreach(w.wl)
      w.w(s"public abstract class $javaClass$typeParamList").braced {
        val skipFirst = SkipFirst()
        generateJavaConstants(w, i.consts)

        val throwException = spec.javaCppException.fold("")(" throws " + _)
        for (m <- i.methods if !m.static) {
          skipFirst { w.wl }
          writeDoc(w, m.doc)
          val ret = marshal.returnType(m.ret)
          val params = m.params.map(p => {
            val nullityAnnotation = marshal.nullityAnnotation(p.ty).map(_ + " ").getOrElse("")
            nullityAnnotation + marshal.paramType(p.ty) + " " + idJava.local(p.ident)
          })
          marshal.nullityAnnotation(m.ret).foreach(w.wl)
          w.wl("public abstract " + ret + " " + idJava.method(m.ident) + params.mkString("(", ", ", ")") + throwException + ";")
        }
        for (m <- i.methods if m.static) {
          skipFirst { w.wl }
          writeDoc(w, m.doc)
          val ret = marshal.returnType(m.ret)
          val params = m.params.map(p => {
            val nullityAnnotation = marshal.nullityAnnotation(p.ty).map(_ + " ").getOrElse("")
            nullityAnnotation + marshal.paramType(p.ty) + " " + idJava.local(p.ident)
          })
          marshal.nullityAnnotation(m.ret).foreach(w.wl)
          w.wl("public static native "+ ret + " " + idJava.method(m.ident) + params.mkString("(", ", ", ")") + ";")
        }
        if (i.ext.cpp) {
          w.wl
          javaAnnotationHeader.foreach(w.wl)
          w.wl(s"private static final class CppProxy$typeParamList extends $javaClass$typeParamList").braced {
            w.wl("private final long nativeRef;")
            w.wl("private final AtomicBoolean destroyed = new AtomicBoolean(false);")
            w.wl
            w.wl(s"private CppProxy(long nativeRef)").braced {
              w.wl("if (nativeRef == 0) throw new RuntimeException(\"nativeRef is zero\");")
              w.wl(s"this.nativeRef = nativeRef;")
            }
            w.wl
            w.wl("private native void nativeDestroy(long nativeRef);")
            w.wl("public void destroy()").braced {
              w.wl("boolean destroyed = this.destroyed.getAndSet(true);")
              w.wl("if (!destroyed) nativeDestroy(this.nativeRef);")
            }
            w.wl("protected void finalize() throws java.lang.Throwable").braced {
              w.wl("destroy();")
              w.wl("super.finalize();")
            }
            for (m <- i.methods if !m.static) { // Static methods not in CppProxy
              val ret = marshal.returnType(m.ret)
              val returnStmt = m.ret.fold("")(_ => "return ")
              val params = m.params.map(p => marshal.paramType(p.ty) + " " + idJava.local(p.ident)).mkString(", ")
              val args = m.params.map(p => idJava.local(p.ident)).mkString(", ")
              val meth = idJava.method(m.ident)
              w.wl
              w.wl(s"@Override")
              w.wl(s"public $ret $meth($params)$throwException").braced {
                w.wl("assert !this.destroyed.get() : \"trying to use a destroyed object\";")
                w.wl(s"${returnStmt}native_$meth(this.nativeRef${preComma(args)});")
              }
              w.wl(s"private native $ret native_$meth(long _nativeRef${preComma(params)});")
            }
          }
        }
      }
    })
  }

  override def generateRecord(origin: String, ident: Ident, doc: Doc, params: Seq[TypeParam], r: Record) {
    val refs = new JavaRefs()
    r.fields.foreach(f => refs.find(f.ty))

    val javaFinal = if (r.ext.java || r.childTypes.nonEmpty) "" else " final"
    val javaName = if (r.ext.java) ident.name + "_base" else ident.name
    writeJavaFile(javaName, origin, refs.java, w => {
      writeDoc(w, doc)
      javaAnnotationHeader.foreach(w.wl)
      val self = marshal.typename(javaName, r)

      val theParentType = r.parentType
      val theParentRecord = if (theParentType.isDefined) r.parentType.get.body.asInstanceOf[Record] else null
      val extendsFlag = if (theParentRecord != null) {
        val theParentJavaName = if (theParentRecord.ext.java) theParentType.get.ident.name + "_base" else theParentType.get.ident.name
        s" extends " + marshal.typename(theParentJavaName, theParentType.get.body)
      } else {
        ""
      }

      val comparableFlag =
        if (r.derivingTypes.contains(DerivingType.Ord)) {
          s" implements Comparable<$self>"
        } else {
          ""
        }
      w.w(s"public$javaFinal class ${self + javaTypeParams(params)}$extendsFlag$comparableFlag").braced {
        w.wl
        generateJavaConstants(w, r.consts)
        // Field definitions.
        for (f <- r.fields) {
          w.wl
          w.wl(s"/*package*/ final ${marshal.fieldType(f.ty)} ${idJava.field(f.ident)};")
        }

        // Constructor.
        val parentFields = getParentRecordFields( r )
        w.wl
        w.wl(s"public $self(").nestedN(2) {
          val skipFirst = SkipFirst()
          for (f <- parentFields) {
            skipFirst { w.wl(",") }
            marshal.nullityAnnotation(f.ty).map(annotation => w.w(annotation + " "))
            w.w(marshal.typename(f.ty) + " " + idJava.local(f.ident))
          }
          for (f <- r.fields) {
            skipFirst { w.wl(",") }
            marshal.nullityAnnotation(f.ty).map(annotation => w.w(annotation + " "))
            w.w(marshal.typename(f.ty) + " " + idJava.local(f.ident))
          }
          w.wl(") {")
        }
        w.nested {
          if( parentFields.nonEmpty ) {
            w.w("super(")
            val skipFirst = SkipFirst()
            for (f <- parentFields) {
              skipFirst {
                w.w(",")
              }
              w.w(idJava.local(f.ident))
            }
            w.wl(");")
          }
          for (f <- r.fields) {
            w.wl(s"this.${idJava.field(f.ident)} = ${idJava.local(f.ident)};")
          }
        }
        w.wl("}")

        // Accessors
        for (f <- r.fields) {
          w.wl
          writeDoc(w, f.doc)
          marshal.nullityAnnotation(f.ty).foreach(w.wl)
          w.w("public " + marshal.typename(f.ty) + " " + idJava.method("get_" + f.ident.name) + "()").braced {
            w.wl("return " + idJava.field(f.ident) + ";")
          }
        }

        if (r.derivingTypes.contains(DerivingType.Eq)) {
          w.wl
          w.wl("@Override")
          val nullableAnnotation = javaNullableAnnotation.map(_ + " ").getOrElse("")
          w.w(s"public boolean equals(${nullableAnnotation}Object obj)").braced {
            w.w(s"if (!(obj instanceof $self))").braced {
              w.wl("return false;")
            }
            w.wl(s"$self other = ($self) obj;")
            w.w(s"return ").nestedN(2) {
              val skipFirst = SkipFirst()
              for (f <- r.fields) {
                skipFirst { w.wl(" &&") }
                f.ty.resolved.base match {
                  case MBinary => w.w(s"java.util.Arrays.equals(${idJava.field(f.ident)}, other.${idJava.field(f.ident)})")
                  case MList | MSet | MMap => w.w(s"this.${idJava.field(f.ident)}.equals(other.${idJava.field(f.ident)})")
                  case MOptional =>
                    w.w(s"((this.${idJava.field(f.ident)} == null && other.${idJava.field(f.ident)} == null) || ")
                    w.w(s"(this.${idJava.field(f.ident)} != null && this.${idJava.field(f.ident)}.equals(other.${idJava.field(f.ident)})))")
                  case MString => w.w(s"this.${idJava.field(f.ident)}.equals(other.${idJava.field(f.ident)})")
                  case t: MPrimitive => w.w(s"this.${idJava.field(f.ident)} == other.${idJava.field(f.ident)}")
                  case df: MDef => df.defType match {
                    case DRecord => w.w(s"this.${idJava.field(f.ident)}.equals(other.${idJava.field(f.ident)})")
                    case DEnum => w.w(s"this.${idJava.field(f.ident)} == other.${idJava.field(f.ident)}")
                    case _ => throw new AssertionError("Unreachable")
                  }
                  case e: MExtern => e.defType match {
                    case DRecord => if(e.java.reference) {
                      w.w(s"this.${idJava.field(f.ident)}.equals(other.${idJava.field(f.ident)})")
                    } else {
                      w.w(s"this.${idJava.field(f.ident)} == other.${idJava.field(f.ident)}")
                    }
                    case DEnum => w.w(s"this.${idJava.field(f.ident)} == other.${idJava.field(f.ident)}")
                    case _ => throw new AssertionError("Unreachable")
                  }
                  case _ => throw new AssertionError("Unreachable")
                }
              }

              if( theParentType.isDefined ){
                skipFirst { w.wl(" &&") }
                w.w( "super.equals(other)" )
              }
            }

            w.wl(";")
          }
          // Also generate a hashCode function, since you shouldn't override one without the other.
          // This hashcode implementation is based off of the apache commons-lang implementation of
          // HashCodeBuilder (excluding support for Java arrays) which is in turn based off of the
          // the recommendataions made in Effective Java.
          w.wl
          w.wl("@Override")
          w.w("public int hashCode()").braced {
            w.wl("// Pick an arbitrary non-zero starting value")
            w.wl("int hashCode = 17;")
            // Also pick an arbitrary prime to use as the multiplier.
            val multiplier = "31"
            if( theParentType.isDefined ){
              w.wl( s"hashCode = hashCode * $multiplier + super.hashCode();" )
            }
            for (f <- r.fields) {
              val fieldHashCode = f.ty.resolved.base match {
                case MBinary => s"java.util.Arrays.hashCode(${idJava.field(f.ident)})"
                case MList | MSet | MMap | MString | MDate => s"${idJava.field(f.ident)}.hashCode()"
                // Need to repeat this case for MDef
                case df: MDef => s"${idJava.field(f.ident)}.hashCode()"
                case MOptional => s"(${idJava.field(f.ident)} == null ? 0 : ${idJava.field(f.ident)}.hashCode())"
                case t: MPrimitive => t.jName match {
                  case "byte" | "short" | "int" => idJava.field(f.ident)
                  case "long" => s"((int) (${idJava.field(f.ident)} ^ (${idJava.field(f.ident)} >>> 32)))"
                  case "float" => s"Float.floatToIntBits(${idJava.field(f.ident)})"
                  case "double" => s"((int) (Double.doubleToLongBits(${idJava.field(f.ident)}) ^ (Double.doubleToLongBits(${idJava.field(f.ident)}) >>> 32)))"
                  case "boolean" => s"(${idJava.field(f.ident)} ? 1 : 0)"
                  case _ => throw new AssertionError("Unreachable")
                }
                case e: MExtern => e.defType match {
                  case DRecord => "(" + e.java.hash.format(idJava.field(f.ident)) + ")"
                  case DEnum => s"${idJava.field(f.ident)}.hashCode()"
                  case _ => throw new AssertionError("Unreachable")
                }
                case _ => throw new AssertionError("Unreachable")
              }
              w.wl(s"hashCode = hashCode * $multiplier + $fieldHashCode;")
            }
            w.wl(s"return hashCode;")
          }

        }

        w.wl
        w.wl("@Override")
        w.w("public String toString()").braced {
          w.w(s"return ").nestedN(2) {
            w.w(s""""$self{" +""")
            if( theParentType.isDefined ) {
              w.wl(" super.toString() +")
            } else {
              w.wl
            }
            for (i <- r.fields.indices) {
              val name = idJava.field(r.fields(i).ident)
              val comma = if (i > 0) """"," + """ else ""
              w.wl(s"""$comma"$name=" + $name +""")
            }
          }
          w.wl(s""""}";""")
        }
        w.wl

        if (r.derivingTypes.contains(DerivingType.Ord)) {
          def primitiveCompare(ident: Ident) {
            w.wl(s"if (this.${idJava.field(ident)} < other.${idJava.field(ident)}) {").nested {
              w.wl(s"tempResult = -1;")
            }
            w.wl(s"} else if (this.${idJava.field(ident)} > other.${idJava.field(ident)}) {").nested {
              w.wl(s"tempResult = 1;")
            }
            w.wl(s"} else {").nested {
              w.wl(s"tempResult = 0;")
            }
            w.wl("}")
          }
          w.wl
          w.wl("@Override")
          val nonnullAnnotation = javaNonnullAnnotation.map(_ + " ").getOrElse("")
          w.w(s"public int compareTo($nonnullAnnotation$self other) ").braced {
            w.wl("int tempResult;")
            if( theParentType.isDefined ){
              w.wl("tempResult = super.compareTo(other)")
              w.w("if (tempResult != 0)").braced {
                w.wl("return tempResult;")
              }
            }
            for (f <- r.fields) {
              f.ty.resolved.base match {
                case MString => w.wl(s"tempResult = this.${idJava.field(f.ident)}.compareTo(other.${idJava.field(f.ident)});")
                case t: MPrimitive => primitiveCompare(f.ident)
                case df: MDef => df.defType match {
                  case DRecord => w.wl(s"tempResult = this.${idJava.field(f.ident)}.compareTo(other.${idJava.field(f.ident)});")
                  case DEnum => w.w(s"tempResult = this.${idJava.field(f.ident)}.compareTo(other.${idJava.field(f.ident)});")
                  case _ => throw new AssertionError("Unreachable")
                }
                case e: MExtern => e.defType match {
                  case DRecord => if(e.java.reference) w.wl(s"tempResult = this.${idJava.field(f.ident)}.compareTo(other.${idJava.field(f.ident)});") else primitiveCompare(f.ident)
                  case DEnum => w.w(s"tempResult = this.${idJava.field(f.ident)}.compareTo(other.${idJava.field(f.ident)});")
                  case _ => throw new AssertionError("Unreachable")
                }
                case _ => throw new AssertionError("Unreachable")
              }
              w.w("if (tempResult != 0)").braced {
                w.wl("return tempResult;")
              }
            }
            w.wl("return 0;")
          }
        }

      }
    })
  }

  def javaTypeParams(params: Seq[TypeParam]): String =
    if (params.isEmpty) "" else params.map(p => idJava.typeParam(p.ident)).mkString("<", ", ", ">")

}
