import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Api, Artifact } from '../core/api';
import { FieldsComponent, RulesComponent } from '../core/content.components';
import { Notify } from '../core/notify.service';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

const PAGE = 50;

@Component({
  selector: 'app-review',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, StateComponent, RulesComponent, FieldsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    article { border: 1px solid var(--line); border-radius: 4px; margin-bottom: 10px; }
    header { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 4px 8px; background: #eef1f5; }
    .body { padding: 6px 8px; max-height: 320px; overflow: auto; }
    textarea { width: 100%; box-sizing: border-box; height: 240px; font-family: var(--mono); font-size: 12px; }
    .err { color: var(--bad); }
  `,
  template: `
    <h1>Review queue</h1>
    <div class="filters">
      <mat-form-field>
        <mat-label>Status</mat-label>
        <mat-select data-testid="review-status" [ngModel]="status()" (ngModelChange)="status.set($event); offset.set(0); reload()">
          <mat-option value="draft">draft</mat-option>
          <mat-option value="reviewed">reviewed</mat-option>
          <mat-option value="rejected">rejected</mat-option>
          <mat-option value="">all</mat-option>
        </mat-select>
      </mat-form-field>
      <mat-form-field>
        <mat-label>Kind</mat-label>
        <mat-select data-testid="review-kind" [ngModel]="kind()" (ngModelChange)="kind.set($event); offset.set(0); reload()">
          <mat-option value="">all</mat-option>
          <mat-option value="summary">summary</mat-option>
          <mat-option value="rule">rule</mat-option>
          <mat-option value="dictionary">dictionary</mat-option>
          <mat-option value="migration_note">migration_note</mat-option>
          <mat-option value="job_narrative">job_narrative</mat-option>
        </mat-select>
      </mat-form-field>
      <span class="spacer"></span>
      <span class="muted">{{ offset() + 1 }}-{{ offset() + (q.data()?.length ?? 0) }}</span>
      <button matButton [disabled]="offset() === 0" (click)="page(-1)">Previous</button>
      <button matButton [disabled]="(q.data()?.length ?? 0) < pageSize" (click)="page(1)">Next</button>
    </div>
    <app-state [r]="q" [empty]="q.data()?.length === 0" emptyText="Nothing to review with these filters.">
      @for (a of q.data(); track a.id) {
        <article [attr.data-artifact]="a.id" [attr.data-kind]="a.kind" data-testid="artifact">
          <header>
            <strong>#{{ a.id }} {{ a.kind }}</strong>
            <span class="mono">{{ a.member }}:{{ a.line_start }}-{{ a.line_end }}</span>
            <span class="badge" [class.good]="a.status === 'reviewed'" [class.bad]="a.status === 'rejected'" data-testid="artifact-status">{{ a.status }}</span>
            @if (a.reviewer) { <span class="muted">by {{ a.reviewer }}</span> }
            <span class="muted">{{ a.model }}</span>
            <span class="spacer"></span>
            <div class="actions">
              <button matButton="filled" [disabled]="a.status === 'reviewed'" (click)="set(a, 'reviewed')">Approve</button>
              <button matButton="outlined" (click)="startEdit(a)">Edit</button>
              <button matButton="outlined" [disabled]="a.status === 'rejected'" (click)="set(a, 'rejected')">Reject</button>
            </div>
          </header>
          <div class="body">
            @if (editing() === a.id) {
              <textarea aria-label="Artifact content as JSON" data-testid="edit-box" [ngModel]="draft()" (ngModelChange)="draft.set($event); editError.set(null)"></textarea>
              @if (editError(); as e) { <p class="err" role="alert">{{ e }}</p> }
              <p class="muted">Saving marks this reviewed. Search shows the corrected words after the next index run.</p>
              <div class="actions">
                <button matButton="filled" data-testid="edit-save" (click)="saveEdit(a)">Save</button>
                <button matButton (click)="editing.set(null)">Cancel</button>
              </div>
            } @else if (a.kind === 'rule') {
              <app-rules [content]="a.content" />
            } @else {
              <app-fields [content]="a.content" />
            }
          </div>
        </article>
      }
    </app-state>
  `,
})
export class ReviewPage {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  readonly pageSize = PAGE;
  readonly status = signal('draft');
  readonly kind = signal('');
  readonly offset = signal(0);
  readonly q = new Remote<Artifact[]>();
  readonly editing = signal<number | null>(null);
  readonly draft = signal('');
  readonly editError = signal<string | null>(null);

  constructor() {
    this.reload();
  }

  reload(): void {
    void this.q.load(() => this.api.artifacts(this.status() || undefined, this.kind() || undefined, PAGE, this.offset()));
  }

  page(dir: number): void {
    this.offset.update((o) => Math.max(0, o + dir * PAGE));
    this.reload();
  }

  async set(a: Artifact, status: string): Promise<void> {
    await this.notify.run(() => this.api.setArtifactStatus(a.id, status), status === 'reviewed' ? `Approved #${a.id}` : `Rejected #${a.id}`);
    this.reload();
  }

  startEdit(a: Artifact): void {
    this.draft.set(JSON.stringify(JSON.parse(a.content), null, 2));
    this.editError.set(null);
    this.editing.set(a.id);
  }

  async saveEdit(a: Artifact): Promise<void> {
    let content: unknown;
    try {
      content = JSON.parse(this.draft());
    } catch (e) {
      this.editError.set(`Not valid JSON: ${(e as Error).message}`);
      return;
    }
    await this.notify.run(() => this.api.editArtifact(a.id, content), `Saved #${a.id}`);
    this.editing.set(null);
    this.reload();
  }
}
