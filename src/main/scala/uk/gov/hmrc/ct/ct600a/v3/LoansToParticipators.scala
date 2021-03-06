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

package uk.gov.hmrc.ct.ct600a.v3

import org.joda.time.LocalDate
import uk.gov.hmrc.ct.box._
import uk.gov.hmrc.ct.ct600.v3.retriever.CT600BoxRetriever
import uk.gov.hmrc.ct.ct600a.v3.formats.LoansFormatter
import uk.gov.hmrc.ct.domain.ValidationConstants._


case class LoansToParticipators(loans: List[Loan] = List.empty) extends CtBoxIdentifier(name = "Loans to participators.") with CtValue[List[Loan]] with Input with ValidatableBox[CT600BoxRetriever] {

  def +(other: LoansToParticipators): LoansToParticipators = new LoansToParticipators(loans ++ other.loans)

  override def value = loans

  override def asBoxString = LoansFormatter.asBoxString(this)

  override def validate(boxRetriever: CT600BoxRetriever): Set[CtValidation] = {
    validateLoanRequired(boxRetriever) ++
    loans.foldRight(Set[CtValidation]())((loan, tail) => loan.validate(boxRetriever, this) ++ tail)
  }

  def validateLoanRequired(boxRetriever: CT600BoxRetriever): Set[CtValidation] = {
    boxRetriever.retrieveLPQ03().value.getOrElse(false) match {
      case true if loans.isEmpty => Set(CtValidation(Some("LoansToParticipators"), "error.loan.required", None))
      case _ => Set.empty
    }
  }
}

case class Loan ( id: String,
                  name: String,
                  amount: Int,
                  repaymentWithin9Months: Option[Repayment] = None,
                  otherRepayments: List[Repayment] = List.empty,
                  writeOffs: List[WriteOff] = List.empty) {

  def validate(boxRetriever: CT600BoxRetriever, loansToParticipators: LoansToParticipators): Set[CtValidation] = {
    validateLoan(invalidLoanNameLength, "error.loan.name.length") ++
    validateLoan(invalidLoanNameUnique(loansToParticipators), "error.loan.uniqueName") ++
    validateLoan(invalidLoanAmount, "error.loan.amount.value") ++
    validateLoan(invalidBalancedAmount, "error.loan.unbalanced", balancedAmountArgs) ++
    repaymentWithin9Months.map(_.validateWithin9Months(boxRetriever, id)).getOrElse(Set()) ++
    otherRepayments.foldRight(Set[CtValidation]())((repayment, tail) => repayment.validateAfter9Months(boxRetriever, id) ++ tail) ++
    writeOffs.foldRight(Set[CtValidation]())((writeOff, tail) => writeOff.validate(boxRetriever, id) ++ tail)
  }

  private def invalidLoanNameLength: Boolean = name.length < 2 || name.length > 56

  private def invalidLoanNameUnique(loansToParticipators: LoansToParticipators): Boolean = {
    loansToParticipators.loans.exists(loan => loan.id != id && loan.name.trim.toLowerCase == name.trim.toLowerCase)
  }

  private def invalidLoanAmount: Boolean = amount < MIN_MONEY_AMOUNT_ALLOWED || amount > MAX_MONEY_AMOUNT_ALLOWED

  private def invalidBalancedAmount: Boolean = amount < totalAmountRepaymentsAndWriteOffs

  def totalAmountRepaymentsAndWriteOffs: Int =
    repaymentWithin9Months.map(_.amount).getOrElse(0) + otherRepayments.foldRight(0)((h, t) => h.amount + t) + writeOffs.foldRight(0)((h, t) => h.amount + t)

  private def balancedAmountArgs: Option[Seq[String]] = Some(Seq(totalAmountRepaymentsAndWriteOffs.toString, amount.toString))

  def validateLoan(invalid: Boolean, errorMsg: String, errorArgs: Option[Seq[String]] = None): Set[CtValidation] = {
   invalid match {
     case true => Set(CtValidation(Some(s"LoansToParticipators"), s"loan.$id.$errorMsg", errorArgs))
     case false => Set.empty
    }
  }

}

case class Repayment(id: String, amount: Int, date: LocalDate, endDateOfAP: Option[LocalDate] = None) extends LoansDateRules {

  val repaymentWithin9monthsErrorCode = "repaymentWithin9Months"
  val repaymentAfter9MonthsErrorCode = "otherRepayment"

  def validateAfter9Months(boxRetriever: CT600BoxRetriever, loanId: String): Set[CtValidation] = {
    validateRepayment(invalidDateAfter9Months(boxRetriever), repaymentAfter9MonthsErrorCode, s"error.$repaymentAfter9MonthsErrorCode.date.range", errorArgsOtherRepaymentsDate(boxRetriever), loanId) ++
    validateRepayment(invalidRepaymentAmount, repaymentAfter9MonthsErrorCode, s"error.$repaymentAfter9MonthsErrorCode.amount.value", None, loanId) ++
    validateRepayment(invalidApEndDateRequired, repaymentAfter9MonthsErrorCode, s"error.$repaymentAfter9MonthsErrorCode.apEndDate.required", None, loanId) ++
    validateRepayment(invalidApEndDateRange(boxRetriever), repaymentAfter9MonthsErrorCode, s"error.$repaymentAfter9MonthsErrorCode.apEndDate.range", errorArgsOtherRepaymentsApEndDate(boxRetriever), loanId)
  }

  def validateWithin9Months(boxRetriever: CT600BoxRetriever, loanId: String): Set[CtValidation] = {
    validateRepayment(invalidDateWithin9Months(boxRetriever), repaymentWithin9monthsErrorCode, s"error.$repaymentWithin9monthsErrorCode.date.range",  errorArgsRepaymentsWith9MonthsDate(boxRetriever), loanId) ++
    validateRepayment(invalidRepaymentAmount, repaymentWithin9monthsErrorCode, s"error.$repaymentWithin9monthsErrorCode.amount.value", None, loanId)
  }

  private def invalidDateWithin9Months(boxRetriever: CT600BoxRetriever): Boolean = !date.isAfter(currentAPEndDate(boxRetriever)) || date.isAfter(earlierOfNowAndAPEndDatePlus9Months(boxRetriever))

  private def invalidDateAfter9Months(boxRetriever: CT600BoxRetriever): Boolean = {
    !(date.isAfter(currentAPEndDatePlus9Months(boxRetriever)) && date.isBefore(LocalDate.now().plusDays(1).toDateTimeAtStartOfDay.toLocalDate))
  }

  private def invalidApEndDateRequired: Boolean = endDateOfAP.isEmpty

  private def invalidApEndDateRange(boxRetriever: CT600BoxRetriever): Boolean = !endDateOfAP.map(_.isAfter(currentAPEndDate(boxRetriever))).getOrElse(true)

  private def invalidRepaymentAmount: Boolean = amount < MIN_MONEY_AMOUNT_ALLOWED || amount > MAX_MONEY_AMOUNT_ALLOWED

  def validateRepayment(invalid: Boolean, errorPrefix: String, errorMsg: String, errorArgs: Option[Seq[String]], loanId: String): Set[CtValidation] = {
    invalid match {
      case true => Set(CtValidation(Some(s"LoansToParticipators"), s"loan.$loanId.$errorPrefix.$id.$errorMsg", errorArgs))
      case false => Set.empty
    }
  }

  def currentAPEndDatePlus9Months(boxRetriever: CT600BoxRetriever): LocalDate = boxRetriever.retrieveCP2().value.plusMonths(9)

  def currentAPEndDate(boxRetriever: CT600BoxRetriever): LocalDate = boxRetriever.retrieveCP2().value

  def earlierOfNowAndAPEndDatePlus9Months(boxRetriever: CT600BoxRetriever): LocalDate = {
    currentAPEndDatePlus9Months(boxRetriever).isBefore(dateAtStartofToday) match {
      case true => currentAPEndDatePlus9Months(boxRetriever)
      case _ => dateAtStartofToday
    }
  }

  def dateAtStartofToday: LocalDate = LocalDate.now().toDateTimeAtStartOfDay.toLocalDate

  def errorArgsRepaymentsWith9MonthsDate(boxRetriever: CT600BoxRetriever): Some[Seq[String]] =
    Some(Seq(toErrorArgsFormat(currentAPEndDate(boxRetriever)), toErrorArgsFormat(earlierOfNowAndAPEndDatePlus9Months(boxRetriever))))

  def errorArgsOtherRepaymentsDate(boxRetriever: CT600BoxRetriever): Some[Seq[String]] =
    Some(Seq(toErrorArgsFormat(currentAPEndDatePlus9Months(boxRetriever).plusDays(1)), toErrorArgsFormat(LocalDate.now())))

  def errorArgsOtherRepaymentsApEndDate(boxRetriever: CT600BoxRetriever): Some[Seq[String]] =
    Some(Seq(toErrorArgsFormat(currentAPEndDate(boxRetriever))))
}

case class WriteOff(id: String, amount: Int, date: LocalDate, endDateOfAP : Option[LocalDate] = None) extends LoansDateRules {

  private val writeOffErrorCode = "writeOff"

  def validate(boxRetriever: CT600BoxRetriever, loanId: String): Set[CtValidation] = {
    validateWriteOff(invalidDate(boxRetriever), s"error.$writeOffErrorCode.date.range", errorArgsWriteOffDate(boxRetriever), loanId) ++
    validateWriteOff(invalidWriteOffAmount, s"error.$writeOffErrorCode.amount.value", None, loanId) ++
    validateWriteOff(invalidApEndDateRequired(boxRetriever), s"error.$writeOffErrorCode.apEndDate.required", None, loanId) ++
    validateWriteOff(invalidApEndDateRange(boxRetriever), s"error.$writeOffErrorCode.apEndDate.range", errorArgsWriteOffApEndDate(boxRetriever), loanId)
  }

  private def invalidDate(boxRetriever: CT600BoxRetriever): Boolean = !(date.isAfter(currentAPEndDate(boxRetriever)) && date.isBefore(LocalDate.now().plusDays(1).toDateTimeAtStartOfDay.toLocalDate))

  private def invalidWriteOffAmount: Boolean = amount < MIN_MONEY_AMOUNT_ALLOWED || amount > MAX_MONEY_AMOUNT_ALLOWED

  private def invalidApEndDateRequired(boxRetriever: CT600BoxRetriever): Boolean = {
    date.isAfter(currentAPEndDatePlus9Months(boxRetriever)) match {
      case true => endDateOfAP.isEmpty
      case _ => false
    }
  }

  private def invalidApEndDateRange(boxRetriever: CT600BoxRetriever): Boolean = !endDateOfAP.map(_.isAfter(currentAPEndDate(boxRetriever))).getOrElse(true)

  def validateWriteOff(invalid: Boolean, errorMsg: String, errorArgs: Option[Seq[String]], loanId: String): Set[CtValidation] = {
    invalid match {
      case true => Set(CtValidation(Some(s"LoansToParticipators"), s"loan.$loanId.$writeOffErrorCode.$id.$errorMsg", errorArgs))
      case false => Set.empty
    }
  }

  def currentAPEndDate(boxRetriever: CT600BoxRetriever): LocalDate = boxRetriever.retrieveCP2().value

  def currentAPEndDatePlus9Months(boxRetriever: CT600BoxRetriever): LocalDate = boxRetriever.retrieveCP2().value.plusMonths(9)

  def errorArgsWriteOffApEndDate(boxRetriever: CT600BoxRetriever): Some[Seq[String]] =
    Some(Seq(toErrorArgsFormat(boxRetriever.retrieveCP2().value)))

  def errorArgsWriteOffDate(boxRetriever: CT600BoxRetriever): Some[Seq[String]] =
    Some(Seq(toErrorArgsFormat(boxRetriever.retrieveCP2().value.plusDays(1)), toErrorArgsFormat(LocalDate.now())))

}


trait LoansDateRules {

  val date: LocalDate
  val endDateOfAP: Option[LocalDate]

  def isReliefEarlierThanDue(acctPeriodEnd: LocalDate): Boolean = {
    val nineMonthsAndADayAfter: LocalDate = acctPeriodEnd.plusMonths(9).plusDays(1)
    date.isAfter(acctPeriodEnd) && date.isBefore(nineMonthsAndADayAfter)
  }

  def isLaterReliefNowDue(acctPeriodEnd: LocalDate, filingDate: LPQ07): Boolean = {
    !isReliefEarlierThanDue(acctPeriodEnd) && isFilingAfterLaterReliefDueDate(filingDate)
  }

  def isLaterReliefNotYetDue(acctPeriodEnd: LocalDate, filingDate: LPQ07): Boolean = {
    !isReliefEarlierThanDue(acctPeriodEnd) && isFilingBeforeReliefDueDate(filingDate)
  }

  private def isFilingAfterLaterReliefDueDate(filingDateBoxNumber: LPQ07): Boolean = {
    (filingDateBoxNumber.value, endDateOfAP) match {
      case (Some(filingDate), Some(endDate)) => {
        val reliefDueDate = endDate.plusMonths(9)
        filingDate.isAfter(reliefDueDate)
      }
      case _ => false
    }
  }

  private def isFilingBeforeReliefDueDate(filingDate: LPQ07) = filingDate.value match {
    case Some(_) => !isFilingAfterLaterReliefDueDate(filingDate)
    case None => true
  }

}
