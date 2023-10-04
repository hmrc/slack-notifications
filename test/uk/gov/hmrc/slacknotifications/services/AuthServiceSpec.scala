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

import uk.gov.hmrc.slacknotifications.model.{ServiceConfig, Password}
import uk.gov.hmrc.slacknotifications.services.AuthService.ClientService
import uk.gov.hmrc.slacknotifications.test.UnitSpec
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig

class AuthServiceSpec extends UnitSpec {

  "Checking if user is authorised" should {

    "return true if the service is present in the configuration" in {
      val service = ClientService("foo", Password("bar"))

      val configuration = mock[SlackNotificationConfig]
      when(configuration.serviceConfigs)
        .thenReturn(List(ServiceConfig(
          name        = service.name,
          password    = service.password,
          displayName = None,
          userEmoji   = None
        )))

      val authService = new AuthService(configuration)

      authService.isAuthorized(service) shouldBe true
    }

    "ignore trailing \n for password in config" in {
      val service = ClientService("foo", Password("bar"))

      val configuration = mock[SlackNotificationConfig]
      when(configuration.serviceConfigs)
        .thenReturn(List(ServiceConfig(
          name        = service.name,
          password    = Password(service.password.value + "\n"),
          displayName = None,
          userEmoji   = None
        )))

      val authService = new AuthService(configuration)

      authService.isAuthorized(service) shouldBe true
    }

    "return false if no matching service is found in config" in {
      val service = ClientService("foo", Password("bar"))

      val configuration = mock[SlackNotificationConfig]
      when(configuration.serviceConfigs)
        .thenReturn(List(ServiceConfig(
          name        = service.name,
          password    = service.password,
          displayName = None,
          userEmoji   = None
        )))

      val authService = new AuthService(configuration)

      val anotherServiceNotInConfig = ClientService("x", Password("y"))

      authService.isAuthorized(anotherServiceNotInConfig) shouldBe false
    }
  }
}
