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

package uk.gov.hmrc.slacknotifications.connectors

import javax.inject.{Inject, Singleton}
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig
)(using ExecutionContext):
  import HttpReads.Implicits._

  private val url  = servicesConfig.baseUrl("teams-and-repositories")

  def getRepositoryDetails(repositoryName: String)(using HeaderCarrier): Future[Option[RepositoryDetails]] =
    httpClientV2
      .get(url"$url/api/v2/repositories/$repositoryName")
      .execute[Option[RepositoryDetails]]

case class RepositoryDetails(
  teamNames  : List[String],
  owningTeams: List[String]
)

object RepositoryDetails:
  given Format[RepositoryDetails] =
    ( (__ \ "teamNames"  ).format[List[String]]
    ~ (__ \ "owningTeams").format[List[String]]
    )(RepositoryDetails.apply, r => Tuple.fromProductTyped(r))
