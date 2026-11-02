import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Api, Deviation, parseJson } from '../core/api';
import { Auth } from '../core/auth.service';
import { Notify } from '../core/notify.service';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

@Component({
  selector: 'app-deviations',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Deviations</h1>
    @if (!auth.isAdmin()) { <p class="muted" data-testid="read-only">Read only: a lead approves or rejects deviations.</p> }
    <div class="filters">
      <mat-form-field>
        <mat-label>Status</mat-label>
        <mat-select [ngModel]="status()" (ngModelChange)="status.set($event); reload()">
          <mat-option value="">all</mat-option>
          <mat-option value="candidate">candidate</mat-option>
          <mat-option value="approved">approved</mat-option>
          <mat-option value="rejected">rejected</mat-option>
        </mat-select>
      </mat-form-field>
      <mat-form-field>
        <mat-label>Program</mat-label>
        <input matInput [ngModel]="program()" (ngModelChange)="program.set($event); reload()" />
      </mat-form-field>
    </div>
    <app-state [r]="q" [empty]="q.data()?.length === 0" emptyText="No deviations with these filters.">
      <div class="scroll-x">
        <table class="grid" data-testid="deviations">
          <thead><tr><th class="num">#</th><th>Legacy</th><th>Modern</th><th>Change</th><th>Reason</th><th>Comparison rule</th><th>Status</th><th>By</th>@if (auth.isAdmin()) { <th></th> }</tr></thead>
          <tbody>
            @for (d of q.data(); track d.id) {
              <tr [attr.data-deviation]="d.id">
                <td class="num">{{ d.id }}</td>
                <td class="mono">{{ ref(d.legacy_ref) }}</td>
                <td class="mono">{{ ref(d.modern_ref) }}</td>
                <td>{{ d.change }}</td>
                <td>{{ d.reason }}</td>
                <td class="mono">{{ d.comparison_rule }}</td>
                <td><span class="badge" [class.good]="d.status === 'approved'" [class.bad]="d.status === 'rejected'" data-testid="deviation-state">{{ d.status }}</span></td>
                <td data-testid="deviation-by">{{ d.approved_by }}</td>
                @if (auth.isAdmin()) {
                  <td>
                    <div class="actions">
                      <button matButton="filled" [disabled]="d.status === 'approved'" (click)="decide(d, true)">Approve</button>
                      <button matButton="outlined" [disabled]="d.status === 'rejected'" (click)="decide(d, false)">Reject</button>
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
export class DeviationsPage {
  readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  readonly status = signal('');
  readonly program = signal('');
  readonly q = new Remote<Deviation[]>();

  constructor() {
    this.reload();
  }

  reload(): void {
    void this.q.load(() => this.api.deviations(this.status() || undefined, this.program().trim() || undefined));
  }

  ref(json: string): string {
    const r = parseJson<Record<string, unknown> | null>(json, null);
    return r ? Object.values(r).join(' ') : json;
  }

  async decide(d: Deviation, approve: boolean): Promise<void> {
    await this.notify.run(() => (approve ? this.api.approveDeviation(d.id) : this.api.rejectDeviation(d.id)), approve ? `Approved deviation ${d.id}` : `Rejected deviation ${d.id}`);
    this.reload();
  }
}
