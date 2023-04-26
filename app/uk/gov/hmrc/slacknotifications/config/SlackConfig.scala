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

package uk.gov.hmrc.slacknotifications.config

import play.api.Configuration
import javax.inject.{Inject, Singleton}


@Singleton
class SlackConfig @Inject()(configuration: Configuration) {

  lazy val noTeamFoundAlert: MessageConfig =
    MessageConfig(
      channel   = configuration.get[String]("alerts.slack.noTeamFound.channel"),
      username  = configuration.get[String]("alerts.slack.noTeamFound.username"),
      iconEmoji = configuration.get[String]("alerts.slack.noTeamFound.iconEmoji"),
      text      = configuration.get[String]("alerts.slack.noTeamFound.text")
    )
}

final case class MessageConfig(
  channel   : String,
  username  : String,
  iconEmoji : String,
  text      : String,
)


