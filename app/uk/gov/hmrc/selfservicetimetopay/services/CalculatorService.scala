package uk.gov.hmrc.selfservicetimetopay.services

import java.time.LocalDate

import play.api.Logger._
import uk.gov.hmrc.selfservicetimetopay.models._

import scala.math.BigDecimal.RoundingMode.{FLOOR, HALF_UP}

object CalculatorService extends CalculatorService {
  override val validator: CalculationValidator = CalculationValidator
  override val interestService: InterestRateService = InterestRateService
}

trait CalculatorService {
  val ONE_HUNDRED = 100
  val MONTHS_IN_YEAR = 12
  val PRECISION_2DP = 2
  val PRECISION_10DP = 10

  val validator: CalculationValidator = ???
  val interestService: InterestRateService = ???
  val durationService = DurationService

  private def isDateWithinRange(inputDate: LocalDate, from: LocalDate, to: Option[LocalDate]): Boolean = {
    inputDate.compareTo(from) >= 0 && (inputDate.compareTo(to.getOrElse(LocalDate.MAX)) <= 0)
  }

  private def processLiability(calculation: Calculation): (Liability) => Liability = { liability =>
    val ratesApplicable = interestService.getRatesForPeriod(liability.dueDate, calculation.endDate)

    ratesApplicable.map { rate =>
      val startDateDuringThisRate = isDateWithinRange(liability.dueDate, rate.startDate, rate.endDate)
      val endDateDuringThisRate = isDateWithinRange(calculation.endDate, rate.startDate, rate.endDate)

      val startDate = if (startDateDuringThisRate) {
        liability.dueDate
      } else {
        rate.startDate
      }
      val endDate = if (endDateDuringThisRate) {
        calculation.endDate
      } else {
        rate.endDate.get
      }

      Liability.partialOf(liability, startDate, endDate, rate)
    }.fold(Liability.partialOf(liability, liability.dueDate, calculation.endDate, InterestRate.NONE)) {
      combine(calculation)
    }
  }

  private def combine(calculation: Calculation) = { (l1: Liability, l2: Liability) =>
    val interest = flatInterest(calculation).apply(l2)
    val dayRate1 = l1.rate.dailyRate
    val dayRate2 = l2.rate.dailyRate

    val daysAtRate1 = durationService.getDaysBetween(l1.dueDate, l2.dueDate.minusDays(1))
    val daysAtRate2 = durationService.getDaysBetween(l2.dueDate, l2.endDate)

    val rate = (dayRate1 * daysAtRate1) + (dayRate2 * daysAtRate2) * l2.dueDate.lengthOfYear() / (daysAtRate1 + daysAtRate2)

    val combinedRate = InterestRate(l1.dueDate, Option(l2.endDate), rate)

    Liability(l1.`type`, l1.amount, l1.interestAccrued + interest, calculation.startDate, l1.dueDate, l2.endDate, combinedRate)
  }

  def buildSchedule(calculation: Calculation): PaymentSchedule = {
    val totalInterest = calculation.liabilities.map(processLiability(calculation).andThen(amortizedInterest(calculation))).sum

    val amountToPay = calculation.liabilities.map(_.amount).sum

    val totalForInstalmentPayment = amountToPay - calculation.initialPayment + totalInterest
    val instalmentPaymentDates = durationService.getRepaymentDates(calculation.startDate, calculation.endDate)
    val numberOfInstalments = instalmentPaymentDates.size

    val instalmentPayment = totalForInstalmentPayment / numberOfInstalments
    val finalPayment = totalForInstalmentPayment - instalmentPayment * numberOfInstalments - 1

    val instalments: Seq[Instalment] = instalmentPaymentDates.map { paymentDate =>

      val instalment = paymentDate match {
        case d if d == instalmentPaymentDates.last => Instalment(d, finalPayment)
        case d => Instalment(d, instalmentPayment)
      }

      logger.info("Instalment: {}", instalment)

      instalment
    }

    PaymentSchedule(calculation.initialPayment, amountToPay, amountToPay - calculation.initialPayment, totalInterest, amountToPay + totalInterest, instalments)
  }

  def generateMultipleSchedules(calculation: Calculation): Seq[PaymentSchedule] = {
    validator.validate(calculation)
    Seq(buildSchedule(calculation))
  }

  private def flatInterest(calculation: Calculation): (Liability) => BigDecimal = { l =>
    val numberOfDays = durationService.getDaysBetween(l.dueDate, calculation.startDate)
    val percentageOfYear = numberOfDays / l.dueDate.lengthOfYear()
    val interestToPay = l.amount * l.rate.rate * percentageOfYear / ONE_HUNDRED

    logger.info(s"Liability: £${l.amount}\t${l.dueDate}\t-\t${calculation.startDate}\t@\t${l.rate.rate}\tover\t$numberOfDays\tdays =\t£$interestToPay (simple)")

    interestToPay
  }

  private def amortizedInterest(calculation: Calculation) = { l: Liability =>
    val startDate = Iterable.range(calculation.startDate, l.dueDate).max

    val rate = l.rate.rate
    val endDate = l.endDate
    val numberOfDays = durationService.getDaysBetween(startDate, endDate)
    val principal = calculation.applyInitialPaymentToDebt(l.amount)

    val rateForPeriod = l.rate.rate / MONTHS_IN_YEAR / 100
    val numberOfPeriods = durationService.getRepaymentDates(startDate, endDate).size

    val numerator = rateForPeriod * (1 + rateForPeriod).pow(numberOfPeriods)
    val denominator = (1 + rateForPeriod).pow(numberOfPeriods) - 1

    val amountPerPeriod = principal * numerator / denominator
    val totalRepayable = (amountPerPeriod * numberOfPeriods).setScale(PRECISION_2DP, HALF_UP)
    val interestToPay = (totalRepayable - principal).setScale(PRECISION_2DP, FLOOR)

    logger.info(s"Liability: £$principal\t$startDate\t-\t$endDate\t@\t$rate\tover\t$numberOfDays\tdays =\t£$interestToPay (amortized - no compound), total payable £$totalRepayable")

    interestToPay + l.interestAccrued
  }
}
