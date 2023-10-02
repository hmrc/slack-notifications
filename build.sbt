import play.sbt.PlayImport.PlayKeys

lazy val microservice = Project("slack-notifications", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(majorVersion := 0)
  .settings(PlayKeys.playDefaultPort := 8866)
  .settings(libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(scalaVersion := "2.13.10")
  .settings(scalacOptions += "-Wconf:src=routes/.*:s")
