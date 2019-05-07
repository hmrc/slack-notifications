/*
 * Copyright 2019 HM Revenue & Customs
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
import play.api.Configuration
import pureconfig.error.CannotConvert
import pureconfig.syntax._
import pureconfig.{CamelCase, ConfigFieldMapping, ConfigReader, ProductHint}
import uk.gov.hmrc.http.logging.Authorization

import scala.util.Try

@Singleton
class AuthService @Inject()(configuration: Configuration) {

  import AuthService._

  implicit def hint[T]: ProductHint[T] =
    ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

  implicit val serviceReader =
    ConfigReader[Service]
      .emap {
        case Service(name, Base64String(decoded)) =>
          Right(Service(name, decoded))
        case serviceWithoutBase64EncPass =>
          Left(
            CannotConvert(
              value   = serviceWithoutBase64EncPass.toString,
              toType  = "Service",
              because = "password was not base64 encoded"
            )
          )
      }

  val authConfiguration: AuthConfiguration =
    configuration.underlying.getConfig("auth").toOrThrow[AuthConfiguration]

  def isAuthorized(service: Service): Boolean =
    authConfiguration.authorizedServices.contains(service)
}

object AuthService {

  object Base64String {
    def unapply(s: String): Option[String] = decode(s)

    def decode(s: String): Option[String] =
      Try(new String(BaseEncoding.base64().decode(s))).toOption
  }

  final case class AuthConfiguration(
    authorizedServices: List[Service]
  )

  final case class Service(
    name: String,
    password: String
  )

  object Service {
    def fromAuthorization(authorization: Authorization): Option[Service] =
      Base64String
        .decode(authorization.value.stripPrefix("Basic "))
        .map(_.split(":"))
        .collect {
          case Array(serviceName, password) => Service(serviceName, password)
        }
  }

}
