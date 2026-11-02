import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { Api, Link, parseJson } from '../core/api';
import { Auth } from '../core/auth.service';
import { Notify } from '../core/notify.service';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

@Component({
  selector: 'app-links',
  imports: [FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatSelectModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Link confirmation</h1>
    @if (!auth.isAdmin()) { <p class="muted" data-testid="read-only">Read only: a lead confirms or rejects links.</p> }
    <div class="filters">
      <mat-form-field>
        <mat-label>Status</mat-label>
        <mat-select data-testid="link-status" [ngModel]="status()" (ngModelChange)="status.set($event); reload()">
          <mat-option value="candidate">candidate</mat-option>
          <mat-option value="confirmed">confirmed</mat-option>
          <mat-option value="rejected">rejected</mat-option>
          <mat-option value="">all</mat-option>
        </mat-select>
      </mat-form-field>
    </div>
    <app-state [r]="q" [empty]="q.data()?.length === 0" emptyText="No links with this status.">
      <div class="scroll-x">
        <table class="grid" data-testid="links">
          <thead><tr><th>Legacy</th><th>Modern unit</th><th>Kind</th><th class="num">Conf.</th><th>Signal</th><th>Evidence</th><th>Status</th><th>By</th>@if (auth.isAdmin()) { <th></th> }</tr></thead>
          <tbody>
            @for (l of q.data(); track l.id) {
              <tr [attr.data-link]="l.id" [attr.data-modern]="l.modern_name" [attr.data-source]="l.source">
                <td><a [routerLink]="['/programs', l.legacy]">{{ l.legacy }}</a></td>
                <td><div class="mono">{{ l.modern_name }}</div><div class="mono muted">{{ l.path }}:{{ l.line_start }}</div></td>
                <td class="nowrap">{{ l.modern_kind }}</td>
                <td class="num">{{ l.confidence }}</td>
                <td class="nowrap">{{ l.source }}</td>
                <td>{{ evidence(l) }}</td>
                <td><span class="badge" [class.good]="l.status === 'confirmed'" [class.bad]="l.status === 'rejected'" data-testid="link-state">{{ l.status }}</span></td>
                <td>{{ l.confirmed_by }}</td>
                @if (auth.isAdmin()) {
                  <td>
                    <div class="actions">
                      <button matButton="filled" [disabled]="l.status === 'confirmed'" (click)="confirm(l)">Confirm</button>
                      <button matButton="outlined" [disabled]="l.status === 'rejected'" (click)="reject(l)">Reject</button>
                    </div>
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    </app-state>
  `,
})
export class LinksPage {
  readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  readonly status = signal('');
  readonly q = new Remote<Link[]>();

  constructor() {
    this.reload();
  }

  reload(): void {
    void this.q.load(() => this.api.links(this.status() || undefined));
  }

  evidence(l: Link): string {
    const e = parseJson<Record<string, string>>(l.evidence, {});
    return e['subject'] ?? e['signal'] ?? l.evidence;
  }

  async confirm(l: Link): Promise<void> {
    await this.notify.run(() => this.api.confirmLink(l.id), `Confirmed ${l.legacy} to ${l.modern_name}`);
    this.reload();
  }

  async reject(l: Link): Promise<void> {
    await this.notify.run(() => this.api.rejectLink(l.id), `Rejected ${l.legacy} to ${l.modern_name}`);
    this.reload();
  }
}
