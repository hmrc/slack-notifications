import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings
import com.geirsson.coursiersmall.{Repository => R}

scalafixResolvers in ThisBuild += new R.Maven("https://artefacts.tax.service.gov.uk/artifactory/hmrc-releases")
scalafixDependencies in ThisBuild := Seq("uk.gov.hmrc" % "scalafix-rules_2.11" % "0.6.0")

val appName = "slack-notifications"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(
    Seq(play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory): _*)
  .settings(majorVersion := 0)
  .settings(publishingSettings: _*)
  .settings(PlayKeys.playDefaultPort := 8866)
  .settings(libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(addCompilerPlugin(scalafixSemanticdb))
  .settings(
      scalacOptions ++= List(
          "-Yrangepos",
          "-Xplugin-require:semanticdb",
          "-P:semanticdb:synthetics:on"
      )
  )
