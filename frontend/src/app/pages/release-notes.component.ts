import { Component, Input, effect, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';
import { ReleaseNotes } from '../core/models';

/**
 * Roadmap (what is planned: issues by TARGET version of unreleased versions, with progress) and
 * Changelog (what was done: resolved issues by FIXED-IN version, newest first). The route's data decides which one.
 */
@Component({
  selector: 'app-release-notes', standalone: true, imports: [RouterLink, DatePipe],
  template: `
  <h1>{{ kind === 'roadmap' ? 'Roadmap' : 'Changelog' }} @if (auth.currentProject()) { <span class="muted">– {{ auth.currentProject()!.name }}</span> }</h1>
  <p class="muted">{{ kind === 'roadmap'
      ? 'Unreleased versions and the issues planned for them (target version). Resolved issues are struck through.'
      : 'Resolved issues by the version they were fixed in (fixed-in version), newest version first.' }}</p>
  @if (error) { <div class="err">{{ error }}</div> }
  @if (data?.truncated) { <div class="err">Very many issues: only the first 5000 were read, the lists below may be incomplete.</div> }
  @for (v of data?.versions ?? []; track v.versionId) {
    <div class="panel">
      <h2>
        @if (!auth.projectId()) { <span class="muted">{{ v.projectName }} – </span> }{{ v.version }}
        @if (v.dateOrder) { <span class="muted" style="font-weight:400">({{ v.dateOrder | date:'yyyy-MM-dd' }})</span> }
        @if (v.released) { <span class="chip s80" style="font-weight:400">released</span> }
      </h2>
      @if (v.description) { <p class="pre" style="margin:0 0 8px">{{ v.description }}</p> }
      @if (kind === 'roadmap') {
        <div class="prog"><div class="done" [style.width.%]="percent(v.resolved, v.total)"></div></div>
        <div class="muted" style="margin-bottom:8px">{{ v.resolved }} of {{ v.total }} issues resolved ({{ percent(v.resolved, v.total) }}%)</div>
      } @else {
        <div class="muted" style="margin-bottom:8px">{{ v.total }} issue(s) fixed</div>
      }
      @for (i of v.issues; track i.id) {
        <div class="it" [class.fin]="i.status >= 80 && kind === 'roadmap'">
          <a [routerLink]="['/issues', i.id]">{{ i.id.toString().padStart(7, '0') }}</a>
          <span class="chip s{{ i.status }}">{{ auth.label('status', i.status) }}</span>
          <span class="sum">{{ i.summary }}</span>
          <span class="muted">{{ i.handler }}</span>
        </div>
      }
    </div>
  } @empty {
    @if (!error) { <div class="panel muted">{{ kind === 'roadmap'
      ? 'No unreleased version has issues with a target version yet.' : 'No resolved issue has a "fixed in" version yet.' }}</div> }
  }`,
  styles: [`
    .prog { height: 10px; background: #e2e8f0; border-radius: 5px; overflow: hidden; margin: 4px 0; }
    .done { height: 100%; background: var(--ok); }
    .it { display: flex; gap: 8px; align-items: center; padding: 3px 0; }
    .it.fin .sum { text-decoration: line-through; color: var(--muted); }
    .sum { flex: 1; }
  `]
})
export class ReleaseNotesComponent {
  /** 'roadmap' | 'changelog' - bound from the route data */
  @Input() kind: 'roadmap' | 'changelog' = 'roadmap';
  auth = inject(AuthService);
  private api = inject(ApiService);
  data: ReleaseNotes | null = null; error = '';

  constructor() {
    effect(() => {
      const pid = this.auth.projectId();
      const call = this.kind === 'changelog' ? this.api.changelog(pid) : this.api.roadmap(pid);
      call.subscribe({ next: d => { this.data = d; this.error = ''; }, error: e => { this.data = null; this.error = errMsg(e); } });
    });
  }
  percent(done: number, total: number) { return total ? Math.round((done / total) * 100) : 0; }
}
