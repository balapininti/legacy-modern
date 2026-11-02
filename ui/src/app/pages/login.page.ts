import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api, AuthConfig, toApiError } from '../core/api';
import { Auth } from '../core/auth.service';
import { Oidc } from '../core/oidc.service';

@Component({
  selector: 'app-login',
  imports: [FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatExpansionModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="login">
      <h1>Mainframe Modernization Toolkit</h1>
      @if (auth.notice(); as n) { <p class="state-msg" role="status">{{ n }}</p> }
      @if (error(); as e) { <p class="state-error" role="alert" data-testid="login-error">{{ e }}</p> }

      <form (submit)="password($event)" class="stack">
        <mat-form-field>
          <mat-label>Username</mat-label>
          <input matInput name="username" autocomplete="username" required [(ngModel)]="username" />
        </mat-form-field>
        <mat-form-field>
          <mat-label>Password</mat-label>
          <input matInput name="password" type="password" autocomplete="current-password" required [(ngModel)]="pass" />
        </mat-form-field>
        <button matButton="filled" type="submit" [disabled]="busy()">Sign in</button>
      </form>

      @if (config(); as c) {
        @if (c.oidc_issuer) {
          <button matButton="outlined" class="sso" data-testid="sso" [disabled]="busy()" (click)="sso(c)">Sign in with SSO</button>
        }
      }

      <mat-accordion class="token-signin">
        <mat-expansion-panel>
          <mat-expansion-panel-header><mat-panel-title>Use an access token</mat-panel-title></mat-expansion-panel-header>
          <form (submit)="useToken($event)" class="stack">
            <p class="muted">For an account with no password: paste the token an admin issued for you.</p>
            <mat-form-field>
              <mat-label>Token</mat-label>
              <input matInput name="token" type="password" autocomplete="off" data-testid="token-input" [(ngModel)]="pasted" />
            </mat-form-field>
            <button matButton="tonal" type="submit" data-testid="token-submit" [disabled]="busy()">Sign in with token</button>
          </form>
        </mat-expansion-panel>
      </mat-accordion>
    </main>
  `,
})
export class LoginPage implements OnInit {
  readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly oidc = inject(Oidc);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly username = signal('');
  readonly pass = signal('');
  readonly pasted = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly config = signal<AuthConfig | null>(null);

  async ngOnInit(): Promise<void> {
    if (this.auth.me()) {
      void this.router.navigateByUrl('/');
      return;
    }
    try {
      this.config.set(await this.api.authConfig());
    } catch (e) {
      this.error.set(toApiError(e).message);
    }
  }

  private returnUrl(): string {
    const r = this.route.snapshot.queryParamMap.get('return');
    // Only a path inside this app.
    return r && r.startsWith('/') && !r.startsWith('//') ? r : '/';
  }

  private async attempt(fn: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await fn();
    } catch (e) {
      this.error.set(toApiError(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  password(ev: Event): Promise<void> {
    ev.preventDefault();
    return this.attempt(async () => {
      await this.auth.loginWithPassword(this.username().trim(), this.pass());
      this.pass.set('');
      await this.router.navigateByUrl(this.returnUrl());
    });
  }

  useToken(ev: Event): Promise<void> {
    ev.preventDefault();
    return this.attempt(async () => {
      await this.auth.adopt(this.pasted().trim());
      this.pasted.set('');
      await this.router.navigateByUrl(this.returnUrl());
    });
  }

  sso(c: AuthConfig): Promise<void> {
    return this.attempt(() => this.oidc.begin(c, this.returnUrl()));
  }
}

@Component({
  selector: 'app-oidc-callback',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="login">
      <h1>Signing in…</h1>
      @if (error(); as e) {
        <p class="state-error" role="alert" data-testid="login-error">{{ e }}</p>
        <a routerLink="/login">Back to sign-in</a>
      }
    </main>
  `,
})
export class OidcCallbackPage implements OnInit {
  private readonly oidc = inject(Oidc);
  private readonly router = inject(Router);
  readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const next = await this.oidc.complete(new URLSearchParams(location.search));
      await this.router.navigateByUrl(next);
    } catch (e) {
      this.error.set(toApiError(e).message);
    }
  }
}
