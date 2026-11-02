import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { parseJson } from './api';

interface Rule { rule_id?: string; statement?: string; condition?: string; action?: string; paragraph?: string; line_start?: number; line_end?: number; confidence?: number }

/** One rule artifact's rules. Interpolation only: artifact text is untrusted and is never rendered as HTML. */
@Component({
  selector: 'app-rules',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (compact()) {
      @for (x of rules(); track $index) {
        <div class="rule">
          <div class="head">
            <span class="mono">{{ x.rule_id }}</span>
            <button type="button" class="linklike" (click)="lineClick.emit(x.line_start ?? 1)">lines {{ x.line_start }}-{{ x.line_end }}</button>
          </div>
          <div>{{ x.statement }}</div>
          @if (x.condition) { <div class="muted">When: {{ x.condition }}</div> }
          @if (x.action) { <div class="muted">Then: {{ x.action }}</div> }
        </div>
      }
    } @else {
    <table class="grid wrap">
      <thead><tr><th>Rule</th><th>Statement</th><th>Condition</th><th>Action</th><th class="num">Lines</th></tr></thead>
      <tbody>
        @for (x of rules(); track $index) {
          <tr>
            <td class="nowrap mono">{{ x.rule_id }}</td>
            <td>{{ x.statement }}</td>
            <td>{{ x.condition }}</td>
            <td>{{ x.action }}</td>
            <td class="num nowrap">{{ x.line_start }}-{{ x.line_end }}</td>
          </tr>
        }
      </tbody>
    </table>
    }
  `,
  styles: `
    .rule { border-bottom: 1px solid var(--line); padding: 5px 0; font-size: 12.5px; }
    .head { display: flex; justify-content: space-between; font-weight: 600; }
  `,
})
export class RulesComponent {
  readonly content = input.required<string>();
  /** In a narrow panel: stacked, with the lines as a link to the source beside it. */
  readonly compact = input(false);
  readonly lineClick = output<number>();
  readonly rules = computed(() => parseJson<{ rules?: Rule[] }>(this.content(), {}).rules ?? []);
}

/** Any other artifact: its fields as label and text. */
@Component({
  selector: 'app-fields',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dl class="fields">
      @for (f of fields(); track f.key) {
        <dt>{{ f.key }}</dt>
        <dd [class.pre]="f.pre">{{ f.value }}</dd>
      }
    </dl>
  `,
})
export class FieldsComponent {
  readonly content = input.required<string>();
  readonly fields = computed(() => {
    const parsed = parseJson<unknown>(this.content(), null);
    if (parsed === null || typeof parsed !== 'object') return [{ key: 'content', value: this.content(), pre: true }];
    return Object.entries(parsed as Record<string, unknown>).map(([key, v]) => {
      if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return { key, value: String(v), pre: false };
      if (Array.isArray(v) && v.every((x) => typeof x === 'string')) return { key, value: v.join(', '), pre: false };
      return { key, value: JSON.stringify(v, null, 1), pre: true };
    });
  });
}
