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

import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from "@angular/core";
import {PluginConfigurationComponent, PluginConfigurationData} from "@valtimo/plugin";
import {SelectItem} from "@valtimo/components";
import {BehaviorSubject, combineLatest, Observable, Subscription, take} from "rxjs";
import {
  AUTHENTICATION_MODES,
  DEFAULT_PORTS,
  ImapMailPluginConfig,
  MAIL_PROTOCOLS,
  MailProtocol,
  POP3_PROTOCOLS,
  POST_PROCESS_ACTIONS,
} from "../../models";

@Component({
  standalone: false,
  selector: "valtimo-imap-mail-plugin-configuration",
  templateUrl: "./imap-mail-plugin-configuration.component.html",
})
export class ImapMailPluginConfigurationComponent
  implements PluginConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<ImapMailPluginConfig>;
  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<PluginConfigurationData> = new EventEmitter<PluginConfigurationData>();

  readonly protocolItems: SelectItem[] = [
    {id: MAIL_PROTOCOLS.IMAPS, text: "IMAP over TLS (imaps, 993)"},
    {id: MAIL_PROTOCOLS.IMAP, text: "IMAP met STARTTLS (imap, 143)"},
    {id: MAIL_PROTOCOLS.POP3S, text: "POP3 over TLS (pop3s, 995)"},
    {id: MAIL_PROTOCOLS.POP3, text: "POP3 met STARTTLS (pop3, 110)"},
  ];

  readonly authenticationItems: SelectItem[] = [
    {id: AUTHENTICATION_MODES.BASIC, text: "Gebruikersnaam en wachtwoord"},
    {id: AUTHENTICATION_MODES.XOAUTH2, text: "OAuth2 / XOAUTH2 (o.a. Microsoft 365)"},
  ];

  readonly postProcessActionItems: SelectItem[] = [
    {id: POST_PROCESS_ACTIONS.MARK_READ, text: "Markeren als gelezen"},
    {id: POST_PROCESS_ACTIONS.MOVE, text: "Verplaatsen naar een andere map"},
    {id: POST_PROCESS_ACTIONS.DELETE, text: "Verwijderen"},
    {id: POST_PROCESS_ACTIONS.NONE, text: "Niets doen (alleen om te testen)"},
  ];

  /**
   * Visibility of the fields that only apply to one branch of the form.
   *
   * These mirror the backend's validation rather than replacing it: the plugin re-checks
   * every combination on each poll, because a configuration can also be created through
   * the API, where this form is not involved at all.
   */
  readonly showPassword$ = new BehaviorSubject<boolean>(true);
  readonly showOAuthFields$ = new BehaviorSubject<boolean>(false);
  readonly showTargetFolder$ = new BehaviorSubject<boolean>(false);
  readonly showFolder$ = new BehaviorSubject<boolean>(true);
  readonly showStartTls$ = new BehaviorSubject<boolean>(false);

  private saveSubscription!: Subscription;
  private prefillSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<ImapMailPluginConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  ngOnInit(): void {
    this.openSaveSubscription();
    this.openPrefillSubscription();
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
    this.prefillSubscription?.unsubscribe();
  }

  formValueChange(formValue: ImapMailPluginConfig): void {
    this.toggleConditionalFields(formValue);
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  /** The port to suggest, following the protocol the user picked. */
  defaultPortFor(protocol?: MailProtocol): number {
    return DEFAULT_PORTS[protocol ?? MAIL_PROTOCOLS.IMAPS];
  }

  /**
   * Puts the conditional fields in the right state on first open.
   *
   * Without this an existing OAuth configuration would open with its credential fields
   * collapsed and look like it has none, until the user touched the dropdown.
   */
  private openPrefillSubscription(): void {
    this.prefillSubscription = this.prefillConfiguration$?.subscribe(prefill => {
      if (prefill) {
        this.toggleConditionalFields(prefill);
      }
    });
  }

  private toggleConditionalFields(formValue: ImapMailPluginConfig): void {
    const protocol = formValue.protocol ?? MAIL_PROTOCOLS.IMAPS;
    const isPop3 = POP3_PROTOCOLS.includes(protocol);
    const usesOAuth = formValue.authentication === AUTHENTICATION_MODES.XOAUTH2;

    this.showPassword$.next(!usesOAuth);
    this.showOAuthFields$.next(usesOAuth);
    this.showTargetFolder$.next(formValue.postProcessAction === POST_PROCESS_ACTIONS.MOVE);
    // POP3 has one implicit folder, so asking which one would be misleading.
    this.showFolder$.next(!isPop3);
    // Only meaningful on the plaintext ports; imaps and pop3s are TLS from the first byte.
    this.showStartTls$.next(protocol === MAIL_PROTOCOLS.IMAP || protocol === MAIL_PROTOCOLS.POP3);
  }

  private handleValid(formValue: ImapMailPluginConfig): void {
    const usesOAuth = formValue.authentication === AUTHENTICATION_MODES.XOAUTH2;
    const credentialsPresent = usesOAuth
      ? !!(formValue.oauthTokenUrl && formValue.oauthClientId && formValue.oauthClientSecret)
      : !!formValue.password;
    const targetFolderPresent =
      formValue.postProcessAction !== POST_PROCESS_ACTIONS.MOVE || !!formValue.targetFolder;

    const valid = !!(
      formValue.configurationTitle &&
      formValue.host &&
      formValue.username &&
      credentialsPresent &&
      targetFolderPresent
    );

    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$])
        .pipe(take(1))
        .subscribe(([formValue, valid]) => {
          if (valid) {
            this.configuration.emit(formValue!);
          }
        });
    });
  }
}
