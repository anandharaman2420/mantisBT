import { Component, effect, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';
import { SummaryReport } from '../core/models';
import { AgeTableComponent, CountTableComponent, DateTableComponent, PeopleTableComponent } from '../shared/summary-tables.component';

/** Summary page (Mantis summary_page.php): where do the issues stand? Follows the project selector. */
@Component({
  selector: 'app-summary', standalone: true,
  imports: [RouterLink, CountTableComponent, PeopleTableComponent, DateTableComponent, AgeTableComponent],
  template: `
  <h1>Summary @if (auth.currentProject()) { <span class="muted">– {{ auth.currentProject()!.name }}</span> }</h1>
  @if (error) { <div class="err">{{ error }}</div> }
  @if (r) {
    <div class="cards">
      <div class="panel"><div class="muted">Total</div><div class="card-num">{{ r.total }}</div></div>
      <div class="panel"><div class="muted">Open</div><div class="card-num" style="color:var(--danger)">{{ r.open }}</div></div>
      <div class="panel"><div class="muted">Resolved</div><div class="card-num" style="color:var(--ok)">{{ r.resolved }}</div></div>
      <div class="panel"><div class="muted">Closed</div><div class="card-num">{{ r.closed }}</div></div>
    </div>
    <div class="grid2">
      <app-count-table title="By status" [rows]="r.byStatus" />
      <app-count-table title="By severity" [rows]="r.bySeverity" />
      <app-count-table title="By priority" [rows]="r.byPriority" />
      <app-count-table title="By resolution" [rows]="r.byResolution" />
      <app-count-table title="By category" [rows]="r.byCategory" />
      @if (r.byProject.length > 1) { <app-count-table title="By project" [rows]="r.byProject" /> }
    </div>
    <div class="grid2">
      <app-people-table title="By developer (assigned to)" [rows]="r.byHandler" />
      <app-people-table title="By reporter" [rows]="r.byReporter" />
    </div>
    <div class="grid2">
      <app-date-table [rows]="r.byDate" />
      <app-age-table [rows]="r.openAge" />
    </div>
    <div class="grid2">
      <div class="panel"><h2>Longest open</h2>
        @for (i of r.longestOpen; track i.id) {
          <div class="it"><a [routerLink]="['/issues', i.id]">{{ i.id.toString().padStart(7, '0') }}</a><span class="chip s{{ i.status }}">{{ auth.label('status', i.status) }}</span>
            <span class="sum">{{ i.summary }}</span><span class="muted">{{ i.ageDays }} days</span></div>
        } @empty { <span class="muted">No open issues.</span> }
      </div>
      <div class="panel"><h2>Most active (notes)</h2>
        @for (i of r.mostActive; track i.id) {
          <div class="it"><a [routerLink]="['/issues', i.id]">{{ i.id.toString().padStart(7, '0') }}</a><span class="chip s{{ i.status }}">{{ auth.label('status', i.status) }}</span>
            <span class="sum">{{ i.summary }}</span><span class="muted">{{ i.notes }} notes</span></div>
        } @empty { <span class="muted">No notes yet.</span> }
      </div>
    </div>
  }`,
  styles: [`
    .cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; } @media (max-width: 800px) { .cards { grid-template-columns: repeat(2, 1fr); } }
    .it { display: flex; gap: 8px; align-items: center; padding: 3px 0; } .sum { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  `]
})
export class SummaryComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  r: SummaryReport | null = null; error = '';

  constructor() {
    effect(() => {
      const pid = this.auth.projectId();
      this.api.summaryReport(pid).subscribe({ next: d => { this.r = d; this.error = ''; }, error: e => { this.r = null; this.error = errMsg(e); } });
    });
  }
}
