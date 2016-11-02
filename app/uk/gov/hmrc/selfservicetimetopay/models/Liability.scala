/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.selfservicetimetopay.models

import java.time.LocalDate

case class Liability(`type`: String, amount: BigDecimal, interestAccrued: BigDecimal, interestCalculationDate: LocalDate, dueDate: LocalDate, endDate: Option[LocalDate] = None, rate: Option[InterestRate] = None)

object Liability {
  def partialOf(l: Liability, startDate: LocalDate, endDate: LocalDate, rate: InterestRate): Liability = {
    Liability(l.`type`, l.amount, l.interestAccrued, l.interestCalculationDate, startDate, Some(endDate), Some(rate))
  }
}