import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Api, Me } from './api';

const KEY = 'toolkit.session';

function read(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

/** Holds the session. The bearer token is the only thing the UI ever writes to localStorage. */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  readonly token = signal<string | null>(read());
  readonly me = signal<Me | null>(null);
  /** Why the user is back at the login page, when it was not their doing. */
  readonly notice = signal<string | null>(null);
  readonly roles = computed(() => this.me()?.roles ?? []);
  readonly isAdmin = computed(() => this.roles().includes('admin'));

  has(...roles: string[]): boolean {
    return roles.some((r) => this.roles().includes(r));
  }

  /** At startup: a stored token is only a session if the API still accepts it. */
  async init(): Promise<void> {
    if (!this.token()) return;
    try {
      this.me.set(await this.api.me());
    } catch {
      this.clear();
    }
  }

  async loginWithPassword(username: string, password: string): Promise<void> {
    const r = await this.api.login(username, password);
    await this.adopt(r.token);
  }

  /** A token is adopted only after the API has said who it belongs to. */
  async adopt(token: string): Promise<void> {
    const previous = this.token();
    this.token.set(token);
    try {
      this.me.set(await this.api.me());
    } catch (e) {
      this.token.set(previous);
      throw e;
    }
    try {
      localStorage.setItem(KEY, token);
    } catch {
      // Private window: the session lasts as long as the page.
    }
    this.notice.set(null);
  }

  async signOut(): Promise<void> {
    try {
      if (this.token()?.startsWith('tk_')) await this.api.logout();
    } catch {
      // Signing out locally still has to work with the API down.
    }
    this.clear();
    await this.router.navigateByUrl('/login');
  }

  /** The API refused the token: revoked, or an SSO token that expired. */
  expired(returnUrl: string): void {
    this.clear();
    this.notice.set('Your session has ended. Sign in again.');
    void this.router.navigate(['/login'], { queryParams: { return: returnUrl } });
  }

  private clear(): void {
    this.token.set(null);
    this.me.set(null);
    try {
      localStorage.removeItem(KEY);
    } catch {
      // nothing stored, then
    }
  }
}
