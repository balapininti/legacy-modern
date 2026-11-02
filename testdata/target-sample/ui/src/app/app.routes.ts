import { Routes } from '@angular/router';
import { SignonComponent } from './signon/signon.component';
import { AccountViewComponent } from './account/account-view.component';

export const routes: Routes = [
  { path: 'signon', component: SignonComponent },
  { path: 'accounts/:id', component: AccountViewComponent },
];
