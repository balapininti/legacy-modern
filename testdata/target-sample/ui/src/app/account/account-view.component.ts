import { Component } from '@angular/core';
import { AccountApi } from './account.service';

/** Replaces map CACTVWA. */
@Component({ selector: 'app-account-view', standalone: true, templateUrl: './account-view.component.html' })
export class AccountViewComponent {
  constructor(private api: AccountApi) {}

  load(id: number) {
    return this.api.view(id);
  }
}
