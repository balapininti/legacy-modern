import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { Api, Capture, Fixture } from '../core/api';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

function pretty(text: string | null): string {
  try {
    return JSON.stringify(JSON.parse(text ?? ''), null, 1);
  } catch {
    return text ?? '';
  }
}

@Component({
  selector: 'app-captures',
  imports: [RouterLink, MatButtonModule, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .pair { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 12px; }
    .pair h3 { font-size: 12.5px; margin: 0 0 4px; }
    pre { margin: 0; border: 1px solid var(--line); background: #fbfbfc; padding: 6px; height: 320px; overflow: auto; font-family: var(--mono); font-size: 11.5px; }
    tr.selected { background: #d3e3fd !important; }
  `,
  template: `
    <h1>Captures</h1>
    <app-state [r]="runs" [empty]="runs.data()?.length === 0" emptyText="No captures yet. Import one with: toolkit capture import.">
      <table class="grid" data-testid="captures">
        <thead><tr><th class="num">#</th><th>Subject</th><th>Kind</th><th>Status</th><th>Source</th><th>Created</th><th class="num">Fixture pairs</th><th></th></tr></thead>
        <tbody>
          @for (c of runs.data(); track c.id) {
            <tr [class.selected]="selected() === c.id" [attr.data-capture]="c.id">
              <td class="num">{{ c.id }}</td>
              <td><a [routerLink]="['/programs', c.subject_id]">{{ c.subject_id }}</a></td>
              <td>{{ c.subject_kind }}</td>
              <td>{{ c.status }}</td>
              <td>{{ c.source }}</td>
              <td class="nowrap">{{ c.created_at }}</td>
              <td class="num">{{ c.fixtures }}</td>
              <td><button matButton (click)="pick(c)">Show fixtures</button></td>
            </tr>
          }
        </tbody>
      </table>
    </app-state>

    @if (selected()) {
      <h2>Fixture pairs of capture {{ selected() }}</h2>
      <app-state [r]="pairs" [empty]="pairs.data()?.length === 0" emptyText="This capture has no fixtures.">
        @for (f of shown(); track f.id) {
          <h2 class="mono">{{ f.name }}</h2>
          <div class="pair" [attr.data-fixture]="f.name">
            <div><h3>Input</h3><pre>{{ f.input }}</pre></div>
            <div><h3>Expected</h3><pre>{{ f.expected }}</pre></div>
          </div>
        }
      </app-state>
    }
  `,
})
export class CapturesPage {
  private readonly api = inject(Api);
  readonly runs = new Remote<Capture[]>();
  readonly pairs = new Remote<Fixture[]>();
  readonly selected = signal<number | null>(null);
  readonly shown = computed(() => (this.pairs.data() ?? []).map((f) => ({ ...f, input: pretty(f.input), expected: pretty(f.expected) })));

  constructor() {
    void this.runs.load(() => this.api.captures());
  }

  pick(c: Capture): void {
    this.selected.set(c.id);
    void this.pairs.load(() => this.api.fixtures(c.id));
  }
}
