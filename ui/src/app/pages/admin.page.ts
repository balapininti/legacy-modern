import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { Api, AuditRow, Jobs, LlmRun, ROLES, TokenRow, UserRow } from '../core/api';
import { Auth } from '../core/auth.service';
import { Notify } from '../core/notify.service';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

const AUDIT_PAGE = 100;

@Component({
  selector: 'app-admin',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatTabsModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .pane { padding: 10px 2px; }
    .once { border: 1px solid var(--warn); background: #fdefcf; padding: 8px 10px; margin-bottom: 10px; border-radius: 4px; }
    .once code { display: block; margin: 4px 0; overflow-wrap: anywhere; user-select: all; font-size: 13px; }
    tfoot td { font-weight: 600; border-top: 2px solid var(--line); padding: 3px 8px; }
  `,
  template: `
    <h1>Admin</h1>
    <mat-tab-group animationDuration="0" [mat-stretch-tabs]="false" (selectedTabChange)="tabChanged($event.index)">
      <mat-tab label="Users and roles">
        <div class="pane">
          <form class="filters" (submit)="addUser($event)">
            <mat-form-field><mat-label>New username</mat-label><input matInput name="username" data-testid="new-user" [(ngModel)]="newUser" /></mat-form-field>
            <mat-form-field>
              <mat-label>Roles</mat-label>
              <mat-select name="roles" multiple data-testid="new-user-roles" [(ngModel)]="newUserRoles">
                @for (r of roles; track r) { <mat-option [value]="r">{{ r }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <button matButton="tonal" type="submit" data-testid="add-user" [disabled]="!newUser().trim()">Add user</button>
          </form>
          <app-state [r]="users" [empty]="users.data()?.length === 0" emptyText="No users.">
            <table class="grid" data-testid="users">
              <thead><tr><th>Username</th><th>Enabled</th>@for (r of roles; track r) { <th>{{ r }}</th> }</tr></thead>
              <tbody>
                @for (u of users.data(); track u.id) {
                  <tr [attr.data-user]="u.username">
                    <td>{{ u.username }}</td>
                    <td>{{ u.enabled ? 'yes' : 'no' }}</td>
                    @for (r of roles; track r) {
                      <td>
                        <mat-checkbox [aria-label]="r + ' for ' + u.username" [checked]="hasRole(u, r)" [disabled]="u.username === auth.me()?.username && r === 'admin'" (change)="toggleRole(u, r, $event.checked)" />
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </app-state>
        </div>
      </mat-tab>

      <mat-tab label="MCP tokens">
        <div class="pane">
          @if (issued(); as t) {
            <div class="once" role="status" data-testid="new-token">
              <strong>Copy this token now. It is shown once and only its hash is kept.</strong>
              <code data-testid="new-token-value">{{ t }}</code>
              <button matButton (click)="issued.set(null)">I have copied it</button>
            </div>
          }
          <form class="filters" (submit)="issue($event)">
            <mat-form-field>
              <mat-label>User</mat-label>
              <mat-select name="u" data-testid="token-user" [(ngModel)]="tokenUser">
                @for (u of users.data(); track u.id) { <mat-option [value]="u.username">{{ u.username }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field><mat-label>Token name</mat-label><input matInput name="n" data-testid="token-name" [(ngModel)]="tokenName" /></mat-form-field>
            <button matButton="tonal" type="submit" data-testid="issue-token" [disabled]="!tokenUser()">Issue token</button>
          </form>
          <app-state [r]="tokens" [empty]="tokens.data()?.length === 0" emptyText="No tokens issued.">
            <table class="grid" data-testid="tokens">
              <thead><tr><th class="num">#</th><th>User</th><th>Name</th><th>Created</th><th>Last used</th><th>Revoked</th><th></th></tr></thead>
              <tbody>
                @for (t of tokens.data(); track t.id) {
                  <tr [attr.data-token]="t.name">
                    <td class="num">{{ t.id }}</td><td>{{ t.username }}</td><td>{{ t.name }}</td><td class="nowrap">{{ t.created_at }}</td><td class="nowrap">{{ t.last_used_at }}</td><td class="nowrap">{{ t.revoked_at }}</td>
                    <td>
                      @if (!t.revoked_at) {
                        @if (confirmingRevoke() === t.id) {
                          <button matButton="outlined" (click)="revoke(t)">Confirm revoke</button>
                          <button matButton (click)="confirmingRevoke.set(null)">Cancel</button>
                        } @else {
                          <button matButton (click)="confirmingRevoke.set(t.id)">Revoke</button>
                        }
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </app-state>
        </div>
      </mat-tab>

      <mat-tab label="Audit log">
        <div class="pane">
          <div class="filters">
            <span class="muted">{{ auditOffset() + 1 }}-{{ auditOffset() + (audit.data()?.length ?? 0) }}</span>
            <button matButton [disabled]="auditOffset() === 0" (click)="auditPage(-1)">Newer</button>
            <button matButton [disabled]="(audit.data()?.length ?? 0) < auditSize" (click)="auditPage(1)">Older</button>
          </div>
          <app-state [r]="audit" [empty]="audit.data()?.length === 0" emptyText="The audit log is empty: it records MCP tool calls.">
            <table class="grid" data-testid="audit">
              <thead><tr><th>Time</th><th>User</th><th>Action</th><th>Tool</th><th class="num">ms</th><th>Outcome</th></tr></thead>
              <tbody>
                @for (a of audit.data(); track $index) {
                  <tr><td class="nowrap">{{ a.ts }}</td><td>{{ a.user }}</td><td>{{ a.action }}</td><td>{{ a.tool }}</td><td class="num">{{ a.latency_ms }}</td><td>{{ a.outcome }}</td></tr>
                }
              </tbody>
            </table>
          </app-state>
        </div>
      </mat-tab>

      <mat-tab label="LLM spend">
        <div class="pane">
          <app-state [r]="llm" [empty]="llm.data()?.length === 0" emptyText="No knowledge runs yet.">
            <table class="grid" data-testid="llm">
              <thead><tr><th class="num">Run</th><th>Model</th><th>Status</th><th class="num">Units</th><th class="num">Tokens in</th><th class="num">Tokens out</th><th class="num">Cost USD</th><th>Started</th><th>Ended</th></tr></thead>
              <tbody>
                @for (r of llm.data(); track r.id) {
                  <tr><td class="num">{{ r.id }}</td><td class="mono">{{ r.model }}</td><td>{{ r.status }}</td><td class="num">{{ r.units }}</td><td class="num">{{ r.tokens_in }}</td><td class="num">{{ r.tokens_out }}</td><td class="num">{{ r.cost_usd }}</td><td class="nowrap">{{ r.started_at }}</td><td class="nowrap">{{ r.ended_at }}</td></tr>
                }
              </tbody>
              <tfoot><tr><td colspan="4">Total</td><td class="num">{{ totals().in }}</td><td class="num">{{ totals().out }}</td><td class="num">{{ totals().cost }}</td><td colspan="2"></td></tr></tfoot>
            </table>
          </app-state>
        </div>
      </mat-tab>

      <mat-tab label="Jobs">
        <div class="pane">
          <div class="filters">
            <button matButton="tonal" data-testid="run-discover" [disabled]="!!jobs.data()?.running" (click)="run('discover')">Re-run discover</button>
            <button matButton="tonal" data-testid="run-index" [disabled]="!!jobs.data()?.running" (click)="run('index')">Re-run index</button>
            <span class="muted" data-testid="job-running">{{ jobs.data()?.running ? jobs.data()?.running + ' is running…' : 'Idle' }}</span>
          </div>
          <p class="muted">The knowledge pass runs from the CLI, where its cost is a deliberate step.</p>
          <app-state [r]="jobs" [empty]="jobs.data()?.recent?.length === 0" emptyText="No batch jobs recorded yet.">
            <table class="grid" data-testid="jobs">
              <thead><tr><th class="num">#</th><th>Job</th><th>Status</th><th>Exit</th><th>Started</th><th>Ended</th></tr></thead>
              <tbody>
                @for (j of jobs.data()?.recent; track j.id) {
                  <tr><td class="num">{{ j.id }}</td><td>{{ j.job_name }}</td><td>{{ j.status }}</td><td>{{ j.exit_code }}</td><td class="nowrap">{{ j.start_time }}</td><td class="nowrap">{{ j.end_time }}</td></tr>
                }
              </tbody>
            </table>
          </app-state>
        </div>
      </mat-tab>
    </mat-tab-group>
  `,
})
export class AdminPage implements OnDestroy {
  readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  readonly roles = ROLES;
  readonly auditSize = AUDIT_PAGE;

  readonly users = new Remote<UserRow[]>();
  readonly tokens = new Remote<TokenRow[]>();
  readonly audit = new Remote<AuditRow[]>();
  readonly llm = new Remote<LlmRun[]>();
  readonly jobs = new Remote<Jobs>();

  readonly newUser = signal('');
  readonly newUserRoles = signal<string[]>([]);
  readonly tokenUser = signal('');
  readonly tokenName = signal('mcp');
  /** In memory only, and gone once dismissed or the page is left. */
  readonly issued = signal<string | null>(null);
  readonly confirmingRevoke = signal<number | null>(null);
  readonly auditOffset = signal(0);
  private poll: ReturnType<typeof setInterval> | undefined;

  readonly totals = computed(() => {
    const rows = this.llm.data() ?? [];
    return {
      in: rows.reduce((n, r) => n + r.tokens_in, 0),
      out: rows.reduce((n, r) => n + r.tokens_out, 0),
      cost: rows.reduce((n, r) => n + Number(r.cost_usd), 0).toFixed(4),
    };
  });

  constructor() {
    this.loadUsers();
  }

  ngOnDestroy(): void {
    clearInterval(this.poll);
  }

  tabChanged(i: number): void {
    if (i === 0) this.loadUsers();
    if (i === 1) {
      this.loadUsers();
      this.loadTokens();
    }
    if (i === 2) this.loadAudit();
    if (i === 3) void this.llm.load(() => this.api.llmRuns());
    if (i === 4) this.loadJobs();
  }

  private loadUsers = () => void this.users.load(() => this.api.users());
  private loadTokens = () => void this.tokens.load(() => this.api.tokens());
  private loadAudit = () => void this.audit.load(() => this.api.audit(AUDIT_PAGE, this.auditOffset()));

  private async loadJobs(): Promise<void> {
    await this.jobs.load(() => this.api.jobs());
    clearInterval(this.poll);
    this.poll = undefined;
    if (this.jobs.data()?.running) {
      this.poll = setInterval(() => void this.loadJobs(), 2000);
    }
  }

  hasRole(u: UserRow, r: string): boolean {
    return u.roles.split(',').includes(r);
  }

  async addUser(ev: Event): Promise<void> {
    ev.preventDefault();
    const name = this.newUser().trim();
    if (!name) return;
    await this.notify.run(() => this.api.addUser(name, this.newUserRoles()), `Added ${name}`);
    this.newUser.set('');
    this.newUserRoles.set([]);
    this.loadUsers();
  }

  async toggleRole(u: UserRow, role: string, on: boolean): Promise<void> {
    const current = u.roles ? u.roles.split(',') : [];
    const next = on ? [...current, role] : current.filter((r) => r !== role);
    await this.notify.run(() => this.api.setRoles(u.id, next), `${u.username}: ${next.join(', ') || 'no roles'}`);
    this.loadUsers();
  }

  async issue(ev: Event): Promise<void> {
    ev.preventDefault();
    const r = await this.notify.run(() => this.api.newToken(this.tokenUser(), this.tokenName().trim() || 'mcp'), `Issued a token for ${this.tokenUser()}`);
    if (r) this.issued.set(r.token);
    this.loadTokens();
  }

  async revoke(t: TokenRow): Promise<void> {
    await this.notify.run(() => this.api.revokeToken(t.id), `Revoked token ${t.id}`);
    this.confirmingRevoke.set(null);
    this.loadTokens();
  }

  auditPage(dir: number): void {
    this.auditOffset.update((o) => Math.max(0, o + dir * AUDIT_PAGE));
    this.loadAudit();
  }

  async run(name: string): Promise<void> {
    await this.notify.run(() => this.api.runJob(name), `Started ${name}`);
    void this.loadJobs();
  }
}
