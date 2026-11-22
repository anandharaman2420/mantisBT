import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { errMsg } from '../core/auth.interceptor';
import { CopyResult } from '../core/models';

/** Copy categories, versions, members and custom fields from another project into this one. */
@Component({
  selector: 'app-copy-setup', standalone: true, imports: [FormsModule],
  template: `
  <p class="muted">Copies the setup of another project into <b>this</b> project. Anything that already exists here is kept and skipped.</p>
  @if (error) { <div class="err">{{ error }}</div> }
  <div class="row" style="align-items:flex-end">
    <div class="grow"><label>Copy from project</label><select [(ngModel)]="source"><option [ngValue]="null">– select –</option>
      @for (o of auth.projectOptions(); track o.project.id) { @if (o.project.id !== projectId) { <option [ngValue]="o.project.id">{{ o.label }}</option> } }</select></div>
  </div>
  <div class="row" style="margin:10px 0">
    <label class="chk"><input type="checkbox" [(ngModel)]="categories"> categories</label>
    <label class="chk"><input type="checkbox" [(ngModel)]="versions"> versions</label>
    <label class="chk"><input type="checkbox" [(ngModel)]="members"> members &amp; roles</label>
    <label class="chk"><input type="checkbox" [(ngModel)]="customFields"> custom fields</label>
  </div>
  <p class="muted">Copying members or custom fields needs the manager role in the source project, too.</p>
  <button class="primary" (click)="run()" [disabled]="!source || busy || !(categories || versions || members || customFields)">{{ busy ? 'Copying…' : 'Copy' }}</button>
  @if (r) {
    <div class="ok" style="margin-top:10px">Copied: <b>{{ r.categories }}</b> categories, <b>{{ r.versions }}</b> versions, <b>{{ r.members }}</b> members, <b>{{ r.customFields }}</b> custom fields.</div>
    @if (r.problems.length) { <div class="err">@for (p of r.problems; track $index) { <div>{{ p.value }}: {{ p.message }}</div> }</div> }
  }`,
  styles: [`.chk { display: inline-flex; gap: 6px; align-items: center; margin-right: 16px; font-weight: 400; font-size: 14px; color: inherit; }`]
})
export class CopySetupComponent {
  auth = inject(AuthService);
  private api = inject(ApiService);
  @Input({ required: true }) projectId!: number;
  @Output() done = new EventEmitter<void>();
  source: number | null = null;
  categories = true; versions = true; members = false; customFields = true;
  busy = false; error = ''; r: CopyResult | null = null;

  run() {
    this.busy = true; this.error = ''; this.r = null;
    this.api.copyFrom(this.projectId, { sourceProjectId: this.source!, categories: this.categories, versions: this.versions,
      members: this.members, customFields: this.customFields }).subscribe({
      next: r => { this.busy = false; this.r = r; this.done.emit(); }, error: e => { this.busy = false; this.error = errMsg(e); }
    });
  }
}
