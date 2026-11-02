import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { Api, GapRow, GapRule } from '../core/api';
import { Auth } from '../core/auth.service';
import { Notify } from '../core/notify.service';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

@Component({
  selector: 'app-gaps',
  imports: [FormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatSelectModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .detail { background: #fff !important; }
    .detail td { padding: 6px 8px 10px 28px; }
    .toggle { width: 18px; }
    .toggle button { font-size: 15px; text-decoration: none; }
    select { font: inherit; }
  `,
  template: `
    <h1>Gap report</h1>
    <div class="stats" data-testid="gap-counts">
      @for (c of counts(); track c.status) { <span><b>{{ c.n }}</b> {{ c.status }}</span> }
    </div>
    <div class="filters">
      <mat-form-field>
        <mat-label>Program</mat-label>
        <input matInput data-testid="gap-filter" [ngModel]="filter()" (ngModelChange)="filter.set($event)" />
      </mat-form-field>
      <mat-form-field>
        <mat-label>Status</mat-label>
        <mat-select [ngModel]="status()" (ngModelChange)="status.set($event)">
          <mat-option value="">all</mat-option>
          <mat-option value="missing">missing</mat-option>
          <mat-option value="partial">partial</mat-option>
          <mat-option value="implemented">implemented</mat-option>
          <mat-option value="replaced_by_design">replaced_by_design</mat-option>
        </mat-select>
      </mat-form-field>
    </div>
    <app-state [r]="q" [empty]="rows().length === 0" emptyText="No program matches.">
      <table class="grid" data-testid="gaps">
        <thead><tr><th class="toggle"></th><th>Program</th><th>Status</th><th>Modern locations</th><th class="num">Rules</th><th class="num">Uncovered</th>@if (canEdit()) { <th>Set status</th> }</tr></thead>
        <tbody>
          @for (g of rows(); track g.program) {
            <tr [attr.data-gap]="g.program">
              <td class="toggle">@if (g.rules.length) { <button class="linklike" [attr.aria-label]="'Rules of ' + g.program" (click)="open.set(open() === g.program ? null : g.program)">{{ open() === g.program ? '▾' : '▸' }}</button> }</td>
              <td><a [routerLink]="['/programs', g.program]">{{ g.program }}</a></td>
              <td><span class="badge" [class.good]="g.status === 'implemented' || g.status === 'replaced_by_design'" [class.warn]="g.status === 'partial'" [class.bad]="g.status === 'missing'" data-testid="gap-status">{{ g.status }}</span></td>
              <td class="mono" data-testid="gap-locations">@for (m of g.modern_locations; track $index) { <div>{{ m.path }}:{{ m.line_start }} <span class="muted">{{ m.name }}</span></div> }</td>
              <td class="num">{{ g.rules_total }}</td>
              <td class="num">{{ g.rules_uncovered }}</td>
              @if (canEdit()) {
                <td>
                  <select [attr.aria-label]="'Set status of ' + g.program" (change)="setStatus(g, $event)">
                    <option value="">Change…</option>
                    <option value="implemented">implemented</option>
                    <option value="partial">partial</option>
                    <option value="missing">missing</option>
                    <option value="replaced_by_design">replaced_by_design</option>
                  </select>
                </td>
              }
            </tr>
            @if (open() === g.program) {
              <tr class="detail">
                <td [attr.colspan]="canEdit() ? 7 : 6">
                  <table class="grid wrap">
                    <thead><tr><th>Rule</th><th>Statement</th><th class="num">Lines</th><th>Artifact</th><th>Coverage</th><th>Modern</th></tr></thead>
                    <tbody>
                      @for (r of g.rules; track r.rule_id) {
                        <tr>
                          <td class="mono nowrap">{{ r.rule_id }}</td>
                          <td>{{ r.statement }}</td>
                          <td class="num nowrap">{{ r.line_start }}-{{ r.line_end }}</td>
                          <td><span class="badge" [class.good]="r.artifact_status === 'reviewed'">{{ r.artifact_status }}</span></td>
                          <td>
                            @if (canEdit()) {
                              <select [attr.aria-label]="'Coverage of ' + r.rule_id" [value]="r.coverage" (change)="setCoverage(r, $event)">
                                <option value="uncovered" [selected]="r.coverage === 'uncovered'">uncovered</option>
                                <option value="covered" [selected]="r.coverage === 'covered'">covered</option>
                                <option value="changed" [selected]="r.coverage === 'changed'">changed</option>
                              </select>
                            } @else { {{ r.coverage }} }
                          </td>
                          <td class="mono">{{ r.modern_name }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </td>
              </tr>
            }
          }
        </tbody>
      </table>
    </app-state>
  `,
})
export class GapsPage {
  private readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  readonly q = new Remote<GapRow[]>();
  readonly filter = signal('');
  readonly status = signal('');
  readonly open = signal<string | null>(null);
  readonly canEdit = computed(() => this.auth.has('developer', 'admin'));
  readonly rows = computed(() => {
    const f = this.filter().trim().toUpperCase();
    return (this.q.data() ?? []).filter((g) => (!f || g.program.toUpperCase().includes(f)) && (!this.status() || g.status === this.status()));
  });
  readonly counts = computed(() => {
    const m = new Map<string, number>();
    for (const g of this.q.data() ?? []) m.set(g.status, (m.get(g.status) ?? 0) + 1);
    return [...m].map(([status, n]) => ({ status, n }));
  });

  constructor() {
    this.reload();
  }

  reload(): void {
    void this.q.load(() => this.api.gapReport());
  }

  async setCoverage(r: GapRule, ev: Event): Promise<void> {
    const status = (ev.target as HTMLSelectElement).value;
    await this.notify.run(() => this.api.confirmCoverage(r.artifact_id, r.rule_id, status), `${r.rule_id} is ${status}`);
    this.reload();
  }

  async setStatus(g: GapRow, ev: Event): Promise<void> {
    const select = ev.target as HTMLSelectElement;
    const status = select.value;
    select.value = '';
    if (!status) return;
    await this.notify.run(() => this.api.setImplementation(g.program, status), `${g.program} is ${status}`);
    this.reload();
  }
}
