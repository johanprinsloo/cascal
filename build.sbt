name := "cascal"

version := "1.3-SNAPSHOT"

organization := "com.shorrockin"

scalaVersion := "2.9.2"

crossScalaVersions := Seq("2.9.1", "2.9.2")

compileOrder := CompileOrder.JavaThenScala

libraryDependencies ++= Seq(
    "org.apache.cassandra" % "cassandra-all" %  "1.1.0",
    "org.apache.cassandra" % "cassandra-thrift" % "1.1.0",
	"com.eaio.uuid" % "uuid" % "3.2",
	"org.slf4j" % "slf4j-api" % "1.6.4",
	"commons-pool" % "commons-pool" % "1.6",
	"junit" % "junit" % "4.10" % "test"
  )
	
libraryDependencies += "com.novocode" % "junit-interface" % "0.7" % "test->default"

publishTo <<= (version) { version: String =>
      Some(Resolver.file("file", new File(Path.userHome.absolutePath+"/.m2/repository") / {
        if  (version.trim.endsWith("SNAPSHOT"))  "snapshots"
        else                                     "releases/" }    ))
}
