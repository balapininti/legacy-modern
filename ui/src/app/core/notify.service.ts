import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { toApiError } from './api';

/** Runs a change against the API and says how it went, in the API's words when it refused. */
@Injectable({ providedIn: 'root' })
export class Notify {
  private readonly snack = inject(MatSnackBar);

  async run<T>(fn: () => Promise<T>, done: string): Promise<T | undefined> {
    try {
      const r = await fn();
      this.snack.open(done, undefined, { duration: 3000 });
      return r;
    } catch (e) {
      const a = toApiError(e);
      this.snack.open(`${a.message}${a.hint ? `: ${a.hint}` : ''}`, 'Dismiss', { duration: 12000, panelClass: 'snack-error' });
      return undefined;
    }
  }
}
