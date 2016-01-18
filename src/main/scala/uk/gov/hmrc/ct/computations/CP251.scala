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

package uk.gov.hmrc.ct.computations

import uk.gov.hmrc.ct.box.{CtTypeConverters, CtBoxIdentifier, CtInteger, Linked}


case class CP251(value: Int) extends CtBoxIdentifier("Expenditure on machinery and plant on which first year allowance is claimed") with CtInteger

object CP251 extends Linked[CP81, CP251] {

  override def apply(source: CP81): CP251 = new CP251(source.value.getOrElse(0))
}
