import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { Api, Tracker, TrackerRow, parseJson } from '../core/api';
import { Auth } from '../core/auth.service';
import { Notify } from '../core/notify.service';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

@Component({
  selector: 'app-tracker',
  imports: [FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .board { display: grid; grid-template-columns: repeat(6, minmax(170px, 1fr)); gap: 8px; height: calc(100vh - 136px); overflow-x: auto; }
    .column { display: flex; flex-direction: column; min-height: 0; background: #f0f3f7; border-radius: 4px; }
    .column h2 { margin: 0; padding: 6px 8px; font-size: 12.5px; }
    .cards { flex: 1; overflow: auto; padding: 0 6px 6px; }
    .card { background: #fff; border: 1px solid var(--line); border-radius: 3px; padding: 4px 6px; margin-bottom: 4px; font-size: 12px; }
    .card .row { display: flex; gap: 6px; align-items: baseline; justify-content: space-between; }
    .ref { overflow-wrap: anywhere; font-family: var(--mono); font-size: 11px; color: var(--muted); }
    .edit { display: flex; flex-direction: column; gap: 4px; margin-top: 4px; }
    .edit select, .edit input { font: inherit; width: 100%; box-sizing: border-box; }
  `,
  template: `
    <div class="filters">
      <h1 style="margin:0">Migration tracker</h1>
      <mat-form-field style="width:240px">
        <mat-label>Filter programs or owners</mat-label>
        <input matInput data-testid="tracker-filter" [ngModel]="filter()" (ngModelChange)="filter.set($event)" />
      </mat-form-field>
    </div>
    <app-state [r]="q" [empty]="q.data()?.programs?.length === 0" emptyText="No programs yet. Run discover from Admin.">
      <div class="board" data-testid="board">
        @for (c of columns(); track c.stage) {
          <section class="column" [attr.data-stage]="c.stage">
            <h2>{{ label(c.stage) }} <span class="count">({{ c.programs.length }})</span></h2>
            <div class="cards">
              @for (p of c.programs; track p.program_id) {
                <div class="card" [attr.data-program]="p.program_id">
                  <div class="row">
                    <a [routerLink]="['/programs', p.program_id]">{{ p.program_id }}</a>
                    @if (canMove()) { <button class="linklike" [attr.aria-label]="'Edit ' + p.program_id" (click)="openEdit(p)">edit</button> }
                  </div>
                  <div class="row muted"><span>{{ p.owner || 'no owner' }}</span><span data-testid="tests" title="tests passing / tests">{{ p.tests_passing }}/{{ p.test_count }} tests</span></div>
                  @for (r of refs(p); track r) { <div class="ref">{{ r }}</div> }
                  @if (editing() === p.program_id) {
                    <div class="edit">
                      <select aria-label="Stage" [(ngModel)]="newStage">
                        @for (s of forward(p); track s) { <option [value]="s">{{ label(s) }}</option> }
                      </select>
                      <input aria-label="Owner" placeholder="Owner" [(ngModel)]="newOwner" />
                      <div class="actions">
                        <button matButton="tonal" (click)="save(p)">Save</button>
                        <button matButton (click)="editing.set(null)">Cancel</button>
                      </div>
                    </div>
                  }
                </div>
              }
            </div>
          </section>
        }
      </div>
    </app-state>
  `,
})
export class TrackerPage {
  private readonly auth = inject(Auth);
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  readonly q = new Remote<Tracker>();
  readonly filter = signal('');
  readonly editing = signal<string | null>(null);
  readonly newStage = signal('');
  readonly newOwner = signal('');
  readonly canMove = computed(() => this.auth.has('admin', 'developer', 'sme-reviewer'));
  readonly columns = computed(() => {
    const d = this.q.data();
    if (!d) return [];
    const f = this.filter().trim().toUpperCase();
    return d.stages.map((stage) => ({
      stage,
      programs: d.programs.filter((p) => p.stage === stage && (!f || p.program_id.toUpperCase().includes(f) || (p.owner ?? '').toUpperCase().includes(f))),
    }));
  });

  constructor() {
    this.reload();
  }

  reload(): void {
    void this.q.load(() => this.api.tracker());
  }

  label(stage: string): string {
    return stage.replace(/_/g, ' ');
  }

  refs(p: TrackerRow): string[] {
    return parseJson<string[]>(p.target_refs, []);
  }

  /** Stages only move forward, and sign-off is the SME's or a lead's. */
  forward(p: TrackerRow): string[] {
    const stages = this.q.data()?.stages ?? [];
    return stages.slice(stages.indexOf(p.stage)).filter((s) => s !== 'sme_signed_off' || this.auth.has('sme-reviewer', 'admin'));
  }

  openEdit(p: TrackerRow): void {
    this.newStage.set(p.stage);
    this.newOwner.set(p.owner ?? '');
    this.editing.set(this.editing() === p.program_id ? null : p.program_id);
  }

  async save(p: TrackerRow): Promise<void> {
    const stage = this.newStage() === p.stage ? undefined : this.newStage();
    const owner = this.newOwner().trim() === (p.owner ?? '') ? undefined : this.newOwner().trim();
    await this.notify.run(() => this.api.moveStage(p.program_id, stage, owner), `Updated ${p.program_id}`);
    this.editing.set(null);
    this.reload();
  }
}
