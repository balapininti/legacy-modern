import { Injectable, inject } from '@angular/core';
import { AuthConfig } from './api';
import { Auth } from './auth.service';

const PENDING = 'toolkit.oidc.pending';

interface Pending { state: string; verifier: string; returnUrl: string; tokenEndpoint: string; clientId: string; redirectUri: string }

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function random(): string {
  return base64url(crypto.getRandomValues(new Uint8Array(32)));
}

/**
 * Authorization code with PKCE against the client's identity provider. The verifier and state live in sessionStorage
 * only until the redirect comes back. The identity provider must allow this origin for discovery and the token call.
 */
@Injectable({ providedIn: 'root' })
export class Oidc {
  private readonly auth = inject(Auth);

  async begin(config: AuthConfig, returnUrl: string): Promise<void> {
    if (!config.oidc_client_id) throw new Error('SSO has no client id: set TOOLKIT_OIDC_CLIENT on the server');
    if (!crypto.subtle) throw new Error('SSO needs a secure context: serve the UI over https');
    const issuer = config.oidc_issuer.replace(/\/$/, '');
    const res = await fetch(`${issuer}/.well-known/openid-configuration`);
    if (!res.ok) throw new Error(`the identity provider answered ${res.status} to discovery`);
    const meta = await res.json();
    if (String(meta.issuer).replace(/\/$/, '') !== issuer) throw new Error('the identity provider reports a different issuer than the one configured');

    const verifier = random();
    const state = random();
    const challenge = base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
    const redirectUri = `${location.origin}/auth/callback`;
    const pending: Pending = { state, verifier, returnUrl, tokenEndpoint: meta.token_endpoint, clientId: config.oidc_client_id, redirectUri };
    sessionStorage.setItem(PENDING, JSON.stringify(pending));

    const url = new URL(meta.authorization_endpoint);
    url.search = new URLSearchParams({
      response_type: 'code',
      client_id: config.oidc_client_id,
      redirect_uri: redirectUri,
      scope: 'openid profile',
      state,
      code_challenge: challenge,
      code_challenge_method: 'S256',
    }).toString();
    location.assign(url.toString());
  }

  /** @return where to go next. */
  async complete(params: URLSearchParams): Promise<string> {
    const raw = sessionStorage.getItem(PENDING);
    sessionStorage.removeItem(PENDING);
    if (!raw) throw new Error('no sign-in is in progress in this tab');
    const p = JSON.parse(raw) as Pending;
    if (params.get('state') !== p.state) throw new Error('the sign-in response does not match this request (state)');
    const denied = params.get('error');
    if (denied) throw new Error(`the identity provider refused: ${params.get('error_description') ?? denied}`);
    const code = params.get('code');
    if (!code) throw new Error('the identity provider sent no authorization code');

    const res = await fetch(p.tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'authorization_code', code, redirect_uri: p.redirectUri, client_id: p.clientId, code_verifier: p.verifier }),
    });
    if (!res.ok) throw new Error(`the token endpoint answered ${res.status}`);
    const token = (await res.json()).access_token;
    if (!token) throw new Error('the token endpoint sent no access token');
    await this.auth.adopt(token);
    return p.returnUrl;
  }
}
