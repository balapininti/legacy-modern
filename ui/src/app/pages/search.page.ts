import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { Api, SearchHit, SearchResult } from '../core/api';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

const KINDS = ['code', 'rule', 'summary', 'dictionary', 'job', 'migration_note', 'modern'];

@Component({
  selector: 'app-search',
  imports: [FormsModule, RouterLink, MatFormFieldModule, MatSelectModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `.snippet { white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--mono); font-size: 11.5px; max-height: 66px; overflow: auto; display: block; }`,
  template: `
    <h1>Search @if (q()) { <span class="muted">"{{ q() }}"</span> }</h1>
    <div class="filters">
      <mat-form-field>
        <mat-label>Kinds</mat-label>
        <mat-select multiple [ngModel]="kinds()" (ngModelChange)="kinds.set($event)">
          @for (k of allKinds; track k) { <mat-option [value]="k">{{ k }}</mat-option> }
        </mat-select>
      </mat-form-field>
      @if (result.data(); as d) { <span class="muted" data-testid="search-mode">{{ d.hits.length }} hits, {{ d.mode }}</span> }
    </div>
    @if (!q()) {
      <p class="state-msg" data-testid="state-empty">Type in the search box above.</p>
    } @else {
      <app-state [r]="result" [empty]="result.data()?.hits?.length === 0" emptyText="Nothing matches. Try other words.">
        <div class="scroll-x">
          <table class="grid" data-testid="search-hits">
            <thead><tr><th class="num">#</th><th>Kind</th><th>Unit</th><th>Where</th><th>Status</th><th class="num">Score</th><th style="width:52%">Text</th></tr></thead>
            <tbody>
              @for (h of result.data()?.hits; track $index) {
                <tr [attr.data-rank]="$index + 1" [attr.data-unit]="h.unit" [attr.data-status]="h.status">
                  <td class="num">{{ $index + 1 }}</td>
                  <td>{{ h.kind }}</td>
                  <td class="mono" style="overflow-wrap:anywhere">{{ h.unit }}</td>
                  <td style="overflow-wrap:anywhere">
                    @if (programOf(h); as p) {
                      <a [routerLink]="['/programs', p]" [queryParams]="{ line: h.lineStart }">{{ h.member }}:{{ h.lineStart }}</a>
                    } @else { {{ h.member }}:{{ h.lineStart }} }
                  </td>
                  <td>@if (h.status) { <span class="badge" [class.good]="h.status === 'reviewed'">{{ h.status }}</span> }</td>
                  <td class="num">{{ h.score }}</td>
                  <td><span class="snippet">{{ h.snippet }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </app-state>
    }
  `,
})
export class SearchPage {
  private readonly api = inject(Api);
  /** ?q=, bound by the router. */
  readonly q = input<string>();
  readonly allKinds = KINDS;
  readonly kinds = signal<string[]>([]);
  readonly result = new Remote<SearchResult>();
  private readonly byMember = signal(new Map<string, string>());
  readonly programOf = (h: SearchHit) => (h.member ? this.byMember().get(h.member) : undefined);
  readonly key = computed(() => `${this.q()}|${this.kinds().join()}`);

  constructor() {
    void this.api.programs().then((r) => this.byMember.set(new Map(r.map((p) => [p.member, p.program_id])))).catch(() => undefined);
    effect(() => {
      this.key();
      const q = this.q();
      const kinds = this.kinds();
      if (q) void this.result.load(() => this.api.search(q, kinds));
    });
  }
}
