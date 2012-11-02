import sbt._
import Keys._
import ScalaBuildKeys.lock
import Project.Initialize

/** This trait stores all the helper methods to generate layers in Scala's layered build. */
trait Layers extends Build {
  // TODO - Clean this up or use a self-type.

  /** Default SBT overrides needed for layered compilation. */
  def settingOverrides: Seq[Setting[_]]
  /** Reference to forkjoin library */
  def forkjoin: Project
  /** Reference to Fast-Java-Bytecode-Generator library */
  def fjbg: Project
  /** Reference to the ASM wrapped project. */
  def asm: Project
  /** A setting that adds some external dependencies. */
  def externalDeps: Setting[_]
  /** The root project. */
  def aaa_root: Project


  // The ever annoying JLINE
  val jline = file("lib") / "jline.jar"

  /** Creates a reference Scala version that can be used to build other projects.   This takes in the raw
    * library, compiler and fjbg libraries as well as a string representing the layer name (used for compiling the compile-interface).
    */
  def makeScalaReference(layer: String, library: Project, reflect: Project, compiler: Project): Setting[Task[ScalaInstance]] =
     scalaInstance <<= (appConfiguration in library,
                        version in library,
                        (exportedProducts in library in Compile),
                        (exportedProducts in reflect in Compile),
                        (exportedProducts in compiler in Compile),
                        (exportedProducts in fjbg in Compile),
                        (exportedProducts in asm in Compile)) map {
    (app, version: String, lib: Classpath, reflect: Classpath, comp: Classpath, fjbg: Classpath, asm: Classpath) =>
      val launcher = app.provider.scalaProvider.launcher
      (lib,comp) match {
         case (Seq(libraryJar), Seq(compilerJar)) =>
           ScalaInstance(
             version + "-" + layer + "-",
             libraryJar.data,
             compilerJar.data,
             launcher,
             ((fjbg.files ++ asm.files ++ reflect.files ++ Seq(jline)):_*))
         case _ => error("Cannot build a ScalaReference with more than one classpath element")
      }
  }
  
  /** Creates a "layer" of Scala compilation.  That is, this will build the next version of Scala from a previous version.
   * Returns the library project and compiler project from the next layer.
   * Note:  The library and compiler are not *complete* in the sense that they are missing things like "actors" and "fjbg".
   */
  def makeLayer(layer: String, referenceScala: Setting[Task[ScalaInstance]], autoLock: Boolean = false): (Project, Project, Project) = {

    // Add the ability to automatically lock a project if compilation was succesfull.
    val autoLockSettings: Seq[Setting[_]] = 
      if(autoLock) Seq(compile in Compile <<= (compile in Compile, lock) apply { (c, l) => 
        c flatMapR { cResult =>
          val result = Result.tryValue(cResult)
          l mapR { tx => result }
        }
      }) 
      else Seq.empty

    // Common settings only needed for bootstrapping.
    def makeCommonSettings(name: String): Seq[Setting[_]] = 
      settingOverrides ++
      autoLockSettings ++
      Seq(
        version := layer,
        scalaSource in Compile <<= (baseDirectory) apply (_ / "src" / name),
        resourceDirectory in Compile <<= baseDirectory apply (_ / "src" / name),   
        resourceGenerators in Compile <+= (resourceManaged, Versions.scalaVersions, skip in Compile, streams) map Versions.generateVersionPropertiesFile(name + ".properties"),
        defaultExcludes in unmanagedResources := ("*.scala" | "*.java" | "*.disabled"),
        defaultExcludes := ("tests"),
        defaultExcludes in unmanagedResources := "*.scala",
        referenceScala
      )

    // Creates a new layered project by name (where name = default src/name location)
    def LayeredProject(name: String): Project = 
      Project(layer + "-" + name, file("."))  settings(makeCommonSettings(name): _*)
 
    val library = LayeredProject("library") settings(
      // TODO - use depends on.
      unmanagedClasspath in Compile <<= (exportedProducts in forkjoin in Compile).identity,
      managedClasspath in Compile := Seq(),
      // TODO - Allow other scalac option settings.
      scalacOptions in Compile <++= (scalaSource in Compile) map (src => Seq("-sourcepath", src.getAbsolutePath))
    )

    // Define the reflection
    val reflect = LayeredProject("reflect") settings(
      // TODO - Use depends on *and* SBT's magic dependency mechanisms...
      unmanagedClasspath in Compile <<= Seq(forkjoin, library).map(exportedProducts in Compile in _).join.map(_.flatten),
      managedClasspath in Compile := Seq()
    )

    // Define the compiler
    val compiler = LayeredProject("compiler") settings(
      // Note: compiler needs MSIL as well.
      unmanagedSourceDirectories in Compile <+= (baseDirectory) apply (_ / "src" / "msil"),
      // Note, we might be able to use the default task, but for some reason ant was filtering files out.  Not sure what's up, but we'll
      // stick with that for now.
      unmanagedResources in Compile <<= (baseDirectory) map {
        (bd) =>
          val dirs = Seq(bd / "src" / "compiler")
          dirs.descendentsExcept( ("*.xml" | "*.html" | "*.gif" | "*.png" | "*.js" | "*.css" | "*.tmpl" | "*.swf" | "*.properties" | "*.txt"),"*.scala").get
      },
      // TODO - Use depends on *and* SBT's magic dependency mechanisms...
      // TODO - jline jar
      unmanagedClasspath in Compile <<= Seq(forkjoin, library, reflect, fjbg, asm).map(exportedProducts in Compile in _).join.map(_.flatten),
      unmanagedClasspath in Compile += jline,
      externalDeps
    )

    // Return the generated projects.
    (library, reflect, compiler)
  }

}
