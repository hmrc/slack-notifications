/*
 * Copyright 2022 HM Revenue & Customs
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
import scala.collection.JavaConverters._


@Singleton
class SlackNotificationConfig @Inject()(configuration: Configuration) {

  val serviceConfigs = {
    def parseService(config: Config): ServiceConfig = {
      val name = config.getString("name")
        ServiceConfig(
          name        = name,
          password    = Password(
                          Base64String.decode(config.getString("password"))
                            .getOrElse(sys.error(s"Could not base64 decode password for $name"))
                            .trim // \n often added when base64 encoding the password for configuration
                        ),
          displayName = getOptionString(config, "displayName"),
          userEmoji   = getOptionString(config, "userEmoji")
        )
      }

    getConfigList(configuration.underlying, "auth.authorizedServices").map(parseService)
  }

  val notRealTeams =
    getCommaSeparatedListFromConfig("exclusions.notRealTeams")

  val notRealGithubUsers =
    getCommaSeparatedListFromConfig("exclusions.notRealGithubUsers")

  private def getCommaSeparatedListFromConfig(key: String): List[String] =
    configuration
      .getOptional[String](key)
      .map { v =>
        v.split(",").map(_.trim).toList
      }
      .getOrElse(Nil)

  private def getConfigList(config: Config, path: String): List[Config] =
    if (config.hasPath(path)) config.getConfigList(path).asScala.toList else List.empty

  private def getOptionString(config: Config, path: String) =
    if (config.hasPath(path)) Some(config.getString(path)) else None
}
