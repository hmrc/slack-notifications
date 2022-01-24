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

package uk.gov.hmrc.slacknotifications.model

import play.api.Configuration
import uk.gov.hmrc.slacknotifications.services.AuthService.Service
import pureconfig.generic.auto._
import uk.gov.hmrc.slacknotifications.test.UnitSpec

class ServiceConfigSpec extends UnitSpec {

  import pureconfig.syntax._
  import ServiceConfig.hint

  "ServiceConfig" should {

    "default displayName end userEmoji if not set" in {
      val service = Service("foo", "bar")
      val configuration =
        Configuration(
          "auth.authorizedServices.0.name"     -> service.name,
          "auth.authorizedServices.0.password" -> service.password
        )

      val config      = configuration.underlying.getList("auth.authorizedServices").toOrThrow[List[ServiceConfig]]

      config shouldBe List(ServiceConfig(service.name, service.password, None, None))
    }

    "use the specified displayName end userEmoji if set" in {
      val service = Service("foo", "bar")
      val configuration =
        Configuration(
          "auth.authorizedServices.0.name"        -> service.name,
          "auth.authorizedServices.0.password"    -> service.password,
          "auth.authorizedServices.0.displayName" -> "custom",
          "auth.authorizedServices.0.userEmoji"   -> ":some-emoji:"
        )

      val config      = configuration.underlying.getList("auth.authorizedServices").toOrThrow[List[ServiceConfig]]

      config shouldBe List(ServiceConfig(service.name, service.password, Some("custom"), Some(":some-emoji:")))
    }
  }

}
