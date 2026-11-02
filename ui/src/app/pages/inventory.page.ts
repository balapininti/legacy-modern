import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { KeyValuePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api, Inventory } from '../core/api';
import { Remote } from '../core/remote';
import { StateComponent } from '../core/state.component';

@Component({
  selector: 'app-inventory',
  imports: [StateComponent, RouterLink, KeyValuePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Inventory</h1>
    <app-state [r]="inv" [empty]="inv.data()?.programs === 0 && !members()" emptyText="Nothing discovered yet. Run discover from Admin.">
      @if (inv.data(); as d) {
        <div class="stats" data-testid="stats">
          <span><b>{{ members() }}</b> members</span>
          <span><b data-testid="stat-programs">{{ d.programs }}</b> programs</span>
          <span><b>{{ d.lines }}</b> lines</span>
          <span><b>{{ d.jobs }}</b> jobs</span>
          <span><b>{{ d.job_steps }}</b> job steps</span>
          <span><b>{{ d.maps }}</b> maps</span>
          <span><b>{{ d.db2_tables }}</b> DB2 tables</span>
          <span><b>{{ d.static_calls.resolved_pct }}%</b> static calls resolved</span>
          <span><b>{{ d.unresolved_dynamic_calls }}</b> unresolved calls</span>
          <span><b>{{ d.parse_failures.length }}</b> parse failures</span>
          <span><b>{{ d.dead_code.length }}</b> dead-code candidates</span>
        </div>

        <div class="cols">
          <section>
            <h2>Members by type</h2>
            <table class="grid" data-testid="by-type">
              <thead><tr><th>Type</th><th class="num">Members</th></tr></thead>
              <tbody>@for (x of d.members_by_type | keyvalue; track x.key) { <tr><td>{{ x.key }}</td><td class="num">{{ x.value }}</td></tr> }</tbody>
            </table>
          </section>
          <section>
            <h2>Members by dialect</h2>
            <table class="grid">
              <thead><tr><th>Dialect</th><th class="num">Members</th></tr></thead>
              <tbody>@for (x of d.members_by_dialect | keyvalue; track x.key) { <tr><td>{{ x.key }}</td><td class="num">{{ x.value }}</td></tr> }</tbody>
            </table>
            <h2>Parse status</h2>
            <table class="grid">
              <thead><tr><th>Status</th><th class="num">Members</th></tr></thead>
              <tbody>@for (x of d.parse_status | keyvalue; track x.key) { <tr><td>{{ x.key }}</td><td class="num">{{ x.value }}</td></tr> }</tbody>
            </table>
          </section>
          <section>
            <h2>Dialect features</h2>
            @if (isEmpty(d.dialect_features)) { <p class="state-msg">None found.</p> } @else {
              <table class="grid" data-testid="dialect-features">
                <thead><tr><th>Feature</th><th class="num">Members</th></tr></thead>
                <tbody>@for (x of d.dialect_features | keyvalue; track x.key) { <tr><td>{{ x.key }}</td><td class="num">{{ x.value }}</td></tr> }</tbody>
              </table>
            }
          </section>
          <section>
            <h2>Dead-code candidates <span class="count">({{ d.dead_code.length }})</span></h2>
            @if (!d.dead_code.length) { <p class="state-msg">None.</p> } @else {
              <table class="grid" data-testid="dead-code">
                <thead><tr><th>Program</th><th>Member</th></tr></thead>
                <tbody>@for (x of d.dead_code; track x.program_id) { <tr><td><a [routerLink]="['/programs', x.program_id]">{{ x.program_id }}</a></td><td>{{ x.path }}</td></tr> }</tbody>
              </table>
            }
            <h2>Calls to programs not in the estate <span class="count">({{ d.calls_to_missing_programs.length }})</span></h2>
            @if (!d.calls_to_missing_programs.length) { <p class="state-msg">None.</p> } @else {
              <table class="grid">
                <thead><tr><th>Target</th><th class="num">Calls</th></tr></thead>
                <tbody>@for (x of d.calls_to_missing_programs; track x.target) { <tr><td class="mono">{{ x.target }}</td><td class="num">{{ x.calls }}</td></tr> }</tbody>
              </table>
            }
          </section>
        </div>

        <h2>Parse failures <span class="count">({{ d.parse_failures.length }})</span></h2>
        @if (!d.parse_failures.length) { <p class="state-msg" data-testid="no-parse-failures">No member failed to parse.</p> } @else {
          <table class="grid" data-testid="parse-failures">
            <thead><tr><th>Member</th><th>Type</th><th class="num">Line</th><th>Error</th></tr></thead>
            <tbody>
              @for (x of d.parse_failures; track x.path) {
                <tr><td>{{ x.path }}</td><td>{{ x.type }}</td><td class="num">{{ errorLine(x.parse_error) }}</td><td class="pre">{{ x.parse_error }}</td></tr>
              }
            </tbody>
          </table>
        }

        <h2>Unresolved CALLs <span class="count">({{ d.unresolved_calls.length }})</span></h2>
        @if (!d.unresolved_calls.length) { <p class="state-msg">None.</p> } @else {
          <table class="grid" data-testid="unresolved">
            <thead><tr><th>Program</th><th>Member</th><th class="num">Line</th><th>Target expression</th><th>Reason</th></tr></thead>
            <tbody>
              @for (x of d.unresolved_calls; track $index) {
                <tr>
                  <td><a [routerLink]="['/programs', x.program]" [queryParams]="{ line: x.line }">{{ x.program }}</a></td>
                  <td>{{ x.member }}</td><td class="num">{{ x.line }}</td><td class="mono">{{ x.target_expr }}</td><td>{{ x.reason }}</td>
                </tr>
              }
            </tbody>
          </table>
        }

        @if (d.missing_copybooks.length) {
          <h2>Missing copybooks <span class="count">({{ d.missing_copybooks.length }})</span></h2>
          <table class="grid"><thead><tr><th>Copybook</th><th>Used by</th></tr></thead>
            <tbody>@for (x of d.missing_copybooks; track $index) { <tr><td class="mono">{{ x.name }}</td><td>{{ x.path }}</td></tr> }</tbody></table>
        }
        @if (d.missing_procs.length) {
          <h2>Missing procs <span class="count">({{ d.missing_procs.length }})</span></h2>
          <table class="grid"><thead><tr><th>Proc</th><th>Used by</th></tr></thead>
            <tbody>@for (x of d.missing_procs; track $index) { <tr><td class="mono">{{ x.proc }}</td><td>{{ x.path }}</td></tr> }</tbody></table>
        }
        <details>
          <summary><h2 style="display:inline">Unclassified members <span class="count">({{ d.unknown_members.length }})</span></h2></summary>
          <table class="grid"><thead><tr><th>Member</th><th>Why</th></tr></thead>
            <tbody>@for (x of d.unknown_members; track x.path) { <tr><td>{{ x.path }}</td><td>{{ x.classify_reason }}</td></tr> }</tbody></table>
        </details>
      }
    </app-state>
  `,
})
export class InventoryPage implements OnInit {
  private readonly api = inject(Api);
  readonly inv = new Remote<Inventory>();
  readonly members = computed(() => Object.values(this.inv.data()?.members_by_type ?? {}).reduce((a, b) => a + b, 0));

  ngOnInit(): void {
    void this.inv.load(() => this.api.inventory());
  }

  isEmpty(o: object): boolean {
    return Object.keys(o).length === 0;
  }

  /** The parsers state a position in their message; it gets its own column when they do. */
  errorLine(error: string | null): string {
    return /\bline[ :]+(\d+)/i.exec(error ?? '')?.[1] ?? /^(\d+):\d+/.exec(error ?? '')?.[1] ?? '';
  }
}
