/*
 * Copyright 2018 HM Revenue & Customs
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

import javax.inject.Inject

import com.google.common.io.BaseEncoding
import play.api.{Configuration, Logger}
import pureconfig.syntax._
import pureconfig.{CamelCase, ConfigFieldMapping, ProductHint}
import uk.gov.hmrc.http.logging.Authorization

class AuthService @Inject()(config: AuthConfiguration) {
  def isAuthorized(service: Service): Boolean = config.authorisedServices.contains(service)
}

class AuthConfiguration @Inject()(configuration: Configuration) {

  implicit def hint[T]: ProductHint[T] =
    ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

  val authorisedServices: Seq[Service] =
    configuration.underlying
      .getConfig("authorisedServices")
      .toOrThrow[Seq[Service]]
      .map { user =>
        val decodedPwd = new String(BaseEncoding.base64().decode(user.password))
        user.copy(password = decodedPwd)
      }

}

case class Service(name: String, password: String)

object Service {

  def base64Decode(s: String): String = new String(BaseEncoding.base64().decode(s))

  def fromAuthorization(authorization: Authorization): Option[Service] =
    base64Decode(authorization.value.stripPrefix("Basic ")).split(":") match {
      case Array(serviceName, password) =>
        Some(Service(serviceName, password))
      case _ =>
        Logger.warn(
          s"Invalid credentials format. Expected: 'Basic [base64(name:password)]'. Got: ${authorization.value}")
        None
    }
}
