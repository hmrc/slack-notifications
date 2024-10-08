/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.slacknotifications

import com.typesafe.config.Config
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.slacknotifications.model.{ServiceConfig, Password}
import uk.gov.hmrc.slacknotifications.services.AuthService.Base64String
import scala.jdk.CollectionConverters._


@Singleton
class SlackNotificationConfig @Inject()(configuration: Configuration):

  val serviceConfigs: Seq[ServiceConfig] =
    def parseService(config: Config): ServiceConfig =
      val name = config.getString("name")
      ServiceConfig(
        name        = name,
        password    = Password(
                        Base64String.decode(config.getString("password"))
                          .getOrElse(sys.error(s"Could not base64 decode password for $name"))
                      ),
        displayName = getOptionString(config, "displayName"),
        userEmoji   = getOptionString(config, "userEmoji")
      )

    getConfigList(configuration.underlying, "auth.authorizedServices").map(parseService)

  val notRealTeams: Seq[String] =
    getCommaSeparatedListFromConfig("exclusions.notRealTeams")

  val notRealGithubUsers: Seq[String] =
    getCommaSeparatedListFromConfig("exclusions.notRealGithubUsers")

  val notificationEnabled: Boolean = configuration.get[Boolean]("slack.notification.enabled")

  private def getCommaSeparatedListFromConfig(key: String): List[String] =
    configuration
      .getOptional[String](key)
      .map(_.split(",").map(_.trim).toList)
      .getOrElse(Nil)

  private def getConfigList(config: Config, path: String): List[Config] =
    if config.hasPath(path) then
      config.getConfigList(path).asScala.toList
    else
      List.empty

  private def getOptionString(config: Config, path: String): Option[String] =
    if config.hasPath(path) then
      Some(config.getString(path))
    else None
