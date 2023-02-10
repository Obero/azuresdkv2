ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.10"

val logger =
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
val logback = "ch.qos.logback" % "logback-classic" % "1.4.5" % Runtime
val loggerDeps: Seq[ModuleID] = Seq(logger, logback)

val azureIdentity = "com.azure" % "azure-identity" % "1.3.6"
val azureResourceManager =
  "com.azure.resourcemanager" % "azure-resourcemanager" % "2.8.0"
val azureMarketplaceOrdering =
  "com.azure.resourcemanager" % "azure-resourcemanager-marketplaceordering" % "1.0.0-beta.2"
val azureBatch = "com.microsoft.azure" % "azure-batch" % "10.1.0"
val azureDeps: Seq[ModuleID] =
  Seq(azureIdentity, azureResourceManager, azureMarketplaceOrdering, azureBatch)

lazy val root = (project in file("."))
  .settings(
    name := "azure-sdkv2",
    idePackagePrefix := Some("io.gatling.thibaut.azuresdkv2"),
    libraryDependencies ++= loggerDeps ++ azureDeps
  )
