import { signal } from '@angular/core';
import { ApiErrorInfo, toApiError } from './api';

/** A list or document from the API with its loading and error state. A newer load wins over an older one still in flight. */
export class Remote<T> {
  readonly data = signal<T | null>(null);
  readonly loading = signal(false);
  readonly error = signal<ApiErrorInfo | null>(null);
  private seq = 0;

  async load(fn: () => Promise<T>): Promise<void> {
    const id = ++this.seq;
    this.loading.set(true);
    this.error.set(null);
    try {
      const v = await fn();
      if (id === this.seq) this.data.set(v);
    } catch (e) {
      if (id === this.seq) {
        this.data.set(null);
        this.error.set(toApiError(e));
      }
    } finally {
      if (id === this.seq) this.loading.set(false);
    }
  }
}
