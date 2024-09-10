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
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.Authorization
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.model.Password

import scala.util.Try

@Singleton
class AuthService @Inject()(slackNotificationConfig: SlackNotificationConfig):
  import AuthService._

  def isAuthorized(clientService: ClientService): Boolean =
    slackNotificationConfig.serviceConfigs
      .find(sc =>
        sc.name          == clientService.name &&
        sc.password.trim == clientService.password.trim // \n often added when base64 encoding the password for configuration
      )
      .isDefined

object AuthService:

  object Base64String:
    def unapply(s: String): Option[String] =
      decode(s)

    def decode(s: String): Option[String] =
      Try(new String(BaseEncoding.base64().decode(s))).toOption

  case class ClientService(
    name    : String,
    password: Password
  )

  object ClientService:
    def fromAuthorization(authorization: Authorization): Option[ClientService] =
      Base64String
        .decode(authorization.value.stripPrefix("Basic "))
        .map(_.split(":"))
        .collect:
          case Array(serviceName, password) => ClientService(serviceName, Password(password))
