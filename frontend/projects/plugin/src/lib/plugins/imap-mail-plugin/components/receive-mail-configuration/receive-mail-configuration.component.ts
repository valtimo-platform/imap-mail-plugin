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
import {FunctionConfigurationComponent, FunctionConfigurationData} from "@valtimo/plugin";
import {BehaviorSubject, combineLatest, Observable, Subscription, switchMap, take} from "rxjs";
import {ReceiveMailConfig} from "../../models";

@Component({
  standalone: false,
  selector: "valtimo-receive-mail-configuration",
  templateUrl: "./receive-mail-configuration.component.html",
})
export class ReceiveMailConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<ReceiveMailConfig>;
  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<FunctionConfigurationData> = new EventEmitter<FunctionConfigurationData>();

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<ReceiveMailConfig | null>(null);

  ngOnInit(): void {
    // Every field is an optional filter, so an untouched form is already valid: it means
    // "start this process for every mail in the mailbox", which is the common case.
    this.valid.emit(true);
    this.openSaveSubscription();
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formValue: ReceiveMailConfig): void {
    this.formValue$.next(formValue);
    this.valid.emit(true);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$
      ?.pipe(switchMap(() => combineLatest([this.formValue$]).pipe(take(1))))
      .subscribe(([formValue]) => {
        this.configuration.emit(formValue ?? {});
      });
  }
}
