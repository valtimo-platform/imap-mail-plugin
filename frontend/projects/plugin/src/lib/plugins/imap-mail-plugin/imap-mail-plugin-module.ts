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

import {NgModule} from "@angular/core";
import {CommonModule} from "@angular/common";
import {PluginTranslatePipeModule} from "@valtimo/plugin";
import {FormModule, InputModule as ValtimoInputModule, SelectModule} from "@valtimo/components";
import {ImapMailPluginConfigurationComponent} from "./components/imap-mail-plugin-configuration/imap-mail-plugin-configuration.component";
import {ReceiveMailConfigurationComponent} from "./components/receive-mail-configuration/receive-mail-configuration.component";

@NgModule({
  declarations: [ImapMailPluginConfigurationComponent, ReceiveMailConfigurationComponent],
  imports: [
    CommonModule,
    PluginTranslatePipeModule,
    FormModule,
    ValtimoInputModule,
    SelectModule,
  ],
  exports: [ImapMailPluginConfigurationComponent, ReceiveMailConfigurationComponent],
})
export class ImapMailPluginModule {}
