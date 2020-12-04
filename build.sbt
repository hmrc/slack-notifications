import play.sbt.PlayImport.PlayKeys.playDefaultPort
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

val appName = "slack-notifications"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(Seq(play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin): _*)
  .settings(majorVersion := 0)
  .settings(publishingSettings: _*)
  .settings(playDefaultPort := 8866)
  .settings(libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(scalacOptions += "-target:jvm-1.8")
  .settings(scalaVersion := "2.12.12")
