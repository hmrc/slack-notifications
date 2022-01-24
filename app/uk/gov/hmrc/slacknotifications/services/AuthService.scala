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

package uk.gov.hmrc.slacknotifications.services

import com.google.common.io.BaseEncoding
import com.typesafe.config.Config
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.http.Authorization
import uk.gov.hmrc.slacknotifications.model.{ServiceConfig, Password}
import scala.collection.JavaConverters._


import scala.util.Try

@Singleton
// TODO rename AuthConfig
class AuthService @Inject()(configuration: Configuration) {
  import AuthService._

  val serviceConfigs = {
    def getOptionString(config: Config, path: String) =
      if (config.hasPath(path)) Some(config.getString(path)) else None

    def parseService(config: Config): ServiceConfig = {
      val name = config.getString("name")
        ServiceConfig(
          name        = name,
          password    = Password(Base64String.decode(config.getString("password")).getOrElse(sys.error(s"Could not base64 decode password for $name"))),
          displayName = getOptionString(config, "displayName"),
          userEmoji   = getOptionString(config, "userEmoji")
        )
      }

    def getConfigList(config: Config, path: String) =
      if (config.hasPath(path)) config.getConfigList(path).asScala.toList else List.empty

    getConfigList(configuration.underlying, "auth.authorizedServices").map(parseService)
  }

  val authConfiguration: AuthConfiguration =
    AuthConfiguration(serviceConfigs.map(sc => Service(name = sc.name, password = sc.password)))

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
    name    : String,
    password: Password
  )

  object Service {
    def fromAuthorization(authorization: Authorization): Option[Service] =
      Base64String
        .decode(authorization.value.stripPrefix("Basic "))
        .map(_.split(":"))
        .collect {
          case Array(serviceName, password) => Service(serviceName, Password(password))
        }
  }
}
