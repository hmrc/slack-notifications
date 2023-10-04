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

package uk.gov.hmrc.slacknotifications.services

import com.google.common.io.BaseEncoding
import play.api.Configuration
import uk.gov.hmrc.slacknotifications.model.{ServiceConfig, Password}
import uk.gov.hmrc.slacknotifications.services.AuthService.ClientService
import uk.gov.hmrc.slacknotifications.test.UnitSpec
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig

class SlackNotificationConfigSpec extends UnitSpec {
  "SlackNotificationConfig" should {
    "fail if password is not base64 encoded" in {
      val configuration =
        Configuration(
          "auth.authorizedServices.0.name"     -> "name",
          "auth.authorizedServices.0.password" -> "not base64 encoded $%£*&^",
          "slack.notification.enabled"         -> true
        )

      val exception = intercept[Exception] {
        new SlackNotificationConfig(configuration)
      }

      exception.getMessage() should include("Could not base64 decode password")
    }

    "default displayName end userEmoji if not set" in {
      val service = ClientService("foo", Password("bar"))
      val configuration =
        Configuration(
          "auth.authorizedServices.0.name"     -> service.name,
          "auth.authorizedServices.0.password" -> base64Encode(service.password.value),
          "slack.notification.enabled"         -> true
        )

      val config = new SlackNotificationConfig(configuration).serviceConfigs

      config shouldBe List(ServiceConfig(service.name, service.password, None, None))
    }

    "use the specified displayName end userEmoji if set" in {
      val service = ClientService("foo", Password("bar"))
      val configuration =
        Configuration(
          "auth.authorizedServices.0.name"        -> service.name,
          "auth.authorizedServices.0.password"    -> base64Encode(service.password.value),
          "auth.authorizedServices.0.displayName" -> "custom",
          "auth.authorizedServices.0.userEmoji"   -> ":some-emoji:",
          "slack.notification.enabled"            -> true
        )

      val config = new SlackNotificationConfig(configuration).serviceConfigs

      config shouldBe List(ServiceConfig(service.name, service.password, Some("custom"), Some(":some-emoji:")))
    }
  }

  def base64Encode(s: String): String =
    BaseEncoding.base64().encode(s.getBytes("UTF-8"))
}
