import { Component, Input } from '@angular/core';
import { SummaryAge, SummaryCount, SummaryDay, SummaryPerson } from '../core/models';

/** Horizontal bars for "label -> open / total" tables of the summary page (no chart library needed). */
@Component({
  selector: 'app-count-table', standalone: true,
  template: `
  <div class="panel"><h2>{{ title }}</h2>
    @for (r of rows; track r.key) {
      <div class="r">
        <span class="lbl" [title]="r.label">{{ r.label }}</span>
        <div class="track"><div class="tot" [style.width.%]="pct(r.total)"></div><div class="opn" [style.width.%]="pct(r.open)"></div></div>
        <span class="num">{{ r.open }} / {{ r.total }}</span>
      </div>
    } @empty { <span class="muted">No issues.</span> }
    @if (rows.length) { <div class="muted legend">open / total</div> }
  </div>`,
  styles: [`
    .r { display: grid; grid-template-columns: 130px 1fr 74px; gap: 8px; align-items: center; margin-bottom: 5px; }
    .lbl { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .track { position: relative; height: 12px; background: #edf2f7; border-radius: 3px; }
    .tot, .opn { position: absolute; left: 0; top: 0; height: 100%; border-radius: 3px; }
    .tot { background: #90cdf4; } .opn { background: var(--primary); }
    .num { text-align: right; font-variant-numeric: tabular-nums; } .legend { margin-top: 6px; font-size: 12px; }
  `]
})
export class CountTableComponent {
  @Input() title = '';
  @Input() rows: SummaryCount[] = [];
  pct(n: number) { const max = Math.max(...this.rows.map(r => r.total), 1); return (n / max) * 100; }
}

/** developers / reporters: open, resolved, closed and a stacked bar */
@Component({
  selector: 'app-people-table', standalone: true,
  template: `
  <div class="panel"><h2>{{ title }}</h2>
    <table><thead><tr><th>Name</th><th class="right">Open</th><th class="right">Resolved</th><th class="right">Closed</th><th class="right">Total</th><th style="width:30%"></th></tr></thead>
      <tbody>@for (p of rows; track p.name) {
        <tr><td>{{ p.name }}</td><td class="right">{{ p.open }}</td><td class="right">{{ p.resolved }}</td><td class="right">{{ p.closed }}</td><td class="right"><b>{{ p.total }}</b></td>
          <td><div class="stack" [style.width.%]="pct(p.total)">
            <span class="o" [style.flex]="p.open"></span><span class="r" [style.flex]="p.resolved"></span><span class="c" [style.flex]="p.closed"></span></div></td></tr>
      } @empty { <tr><td colspan="6" class="muted">No issues.</td></tr> }</tbody></table>
  </div>`,
  styles: [`
    .stack { display: flex; height: 12px; border-radius: 3px; overflow: hidden; min-width: 2px; }
    .o { background: #e53e3e; } .r { background: #68d391; } .c { background: #a0aec0; }
  `]
})
export class PeopleTableComponent {
  @Input() title = '';
  @Input() rows: SummaryPerson[] = [];
  pct(n: number) { const max = Math.max(...this.rows.map(r => r.total), 1); return (n / max) * 100; }
}

@Component({
  selector: 'app-date-table', standalone: true,
  template: `
  <div class="panel"><h2>Opened and resolved by date</h2>
    <table><thead><tr><th>Within the last</th><th class="right">Opened</th><th class="right">Resolved</th><th class="right">Balance</th></tr></thead>
      <tbody>@for (d of rows; track d.days) {
        <tr><td>{{ label(d.days) }}</td><td class="right">{{ d.opened }}</td><td class="right">{{ d.resolved }}</td>
          <td class="right" [style.color]="d.opened - d.resolved > 0 ? 'var(--danger)' : 'var(--ok)'"><b>{{ d.opened - d.resolved > 0 ? '+' : '' }}{{ d.opened - d.resolved }}</b></td></tr>
      }</tbody></table>
    <div class="muted" style="margin-top:6px">Balance = opened - resolved. "Resolved" counts issues whose status was set to resolved or closed in that period (from the history).</div>
  </div>`
})
export class DateTableComponent {
  @Input() rows: SummaryDay[] = [];
  label(days: number) { return days === 1 ? '1 day' : days === 365 ? '1 year' : `${days} days`; }
}

@Component({
  selector: 'app-age-table', standalone: true,
  template: `
  <div class="panel"><h2>Open issues by age</h2>
    @for (a of rows; track a.label) {
      <div class="r"><span>{{ a.label }}</span><div class="track"><div class="fill" [style.width.%]="pct(a.count)"></div></div><span class="num">{{ a.count }}</span></div>
    }
  </div>`,
  styles: [`
    .r { display: grid; grid-template-columns: 150px 1fr 50px; gap: 8px; align-items: center; margin-bottom: 5px; }
    .track { height: 12px; background: #edf2f7; border-radius: 3px; } .fill { height: 100%; background: #ed8936; border-radius: 3px; min-width: 0; } .num { text-align: right; }
  `]
})
export class AgeTableComponent {
  @Input() rows: SummaryAge[] = [];
  pct(n: number) { const max = Math.max(...this.rows.map(r => r.count), 1); return (n / max) * 100; }
}
