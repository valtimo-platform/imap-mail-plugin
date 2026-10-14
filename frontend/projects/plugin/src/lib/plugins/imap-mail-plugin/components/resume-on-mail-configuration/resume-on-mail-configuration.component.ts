/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {Component} from "@angular/core";
import {ReceiveMailConfigurationComponent} from "../receive-mail-configuration/receive-mail-configuration.component";

/**
 * The `resume-on-mail` form: the same three filter fields as `receive-mail`, with its own
 * explanation, because here a value may refer to the waiting case (`pv:`, `doc:`) and that
 * is the normal way to fill it in.
 */
@Component({
  standalone: false,
  selector: "valtimo-resume-on-mail-configuration",
  templateUrl: "./resume-on-mail-configuration.component.html",
})
export class ResumeOnMailConfigurationComponent extends ReceiveMailConfigurationComponent {}
