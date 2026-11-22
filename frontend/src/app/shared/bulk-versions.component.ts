import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { errMsg } from '../core/auth.interceptor';
import { parseRows, readFile, Sep, SEPARATORS, truthy } from '../core/bulk-parse';
import { ImportResult } from '../core/models';
import { ImportResultComponent } from './import-result.component';

@Component({
  selector: 'app-bulk-versions', standalone: true, imports: [FormsModule, ImportResultComponent],
  template: `
  <details class="panel" style="background:#f7fafc;margin-top:14px">
    <summary style="cursor:pointer;font-weight:600">⚡ Bulk add versions</summary>
    <p class="muted" style="margin:8px 0">One version per line. Optional columns: description, released (yes/no), obsolete (yes/no). Existing versions are skipped.</p>
    <textarea rows="6" [(ngModel)]="text" (ngModelChange)="parse()" placeholder="1.0&#10;1.1&#9;Maintenance release&#9;yes&#10;2.0"></textarea>
    <div class="row" style="margin-top:8px;align-items:flex-end">
      <div><label>…or upload</label><input type="file" accept=".csv,.tsv,.txt" (change)="load($event)" style="width:auto"></div>
      <div><label>Column separator</label><select [(ngModel)]="sep" (ngModelChange)="parse()">@for (s of seps; track s.value) { <option [value]="s.value">{{ s.label }}</option> }</select></div>
      <label style="display:flex;gap:5px;align-items:center;font-weight:400;font-size:14px;color:inherit"><input type="checkbox" [(ngModel)]="header" (ngModelChange)="parse()"> first line is a header</label>
    </div>
    @if (rows.length) { <div class="muted" style="margin-top:8px"><b>{{ rows.length }}</b> rows found.</div> }
    @if (error) { <div class="err" style="margin-top:8px">{{ error }}</div> }
    <div style="margin-top:10px"><button class="primary" (click)="run()" [disabled]="!rows.length || busy">Import {{ rows.length }} versions</button></div>
    <app-import-result [r]="result" />
  </details>`
})
export class BulkVersionsComponent {
  private api = inject(ApiService);
  @Input({ required: true }) projectId!: number;
  @Output() done = new EventEmitter<void>();
  seps = SEPARATORS; sep: Sep = 'auto'; header = false; text = '';
  rows: string[][] = []; busy = false; error = ''; result: ImportResult | null = null;

  parse() { this.rows = parseRows(this.text, this.sep, this.header); }
  async load(ev: Event) { const f = (ev.target as HTMLInputElement).files?.[0]; if (f) { this.text = await readFile(f); this.parse(); } }
  run() {
    this.busy = true; this.error = ''; this.result = null;
    this.api.bulkVersions(this.projectId, this.rows.map(r => ({ version: r[0] ?? '', description: r[1] ?? '', released: truthy(r[2]), obsolete: truthy(r[3]) }))).subscribe({
      next: r => { this.busy = false; this.result = r; this.done.emit(); },
      error: e => { this.busy = false; this.error = errMsg(e); }
    });
  }
}
