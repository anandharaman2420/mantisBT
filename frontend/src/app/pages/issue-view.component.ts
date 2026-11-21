import { Component, Input, OnInit, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../core/api.service';
import { errMsg } from '../core/auth.interceptor';
import { AttachmentView, HistoryView, IssueView, NoteView, RelationshipView, UserRef } from '../core/models';

@Component({
  selector: 'app-issue-view', standalone: true, imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  template: `
  @if (error) { <div class="err">{{ error }}</div> }
  @if (i) {
    <div class="row" style="justify-content:space-between;align-items:center;margin-bottom:12px">
      <h1 style="margin:0">{{ i.id.toString().padStart(7,'0') }}: {{ i.summary }} @if (i.viewState === 50) { 🔒 }</h1>
      <div class="row">
        @if (i.canUpdate) { <a [routerLink]="['/issues', i.id, 'edit']"><button>Edit</button></a> }
        <button (click)="toggleMonitor()">{{ i.monitoring ? 'Unmonitor' : 'Monitor' }}</button>
        @if (i.canDelete) { <button class="danger" (click)="remove()">Delete</button> }
      </div>
    </div>

    <div class="panel">
      <div class="grid2">
        <dl class="kv">
          <dt>Project</dt><dd>{{ i.projectName }}</dd>
          <dt>Category</dt><dd>{{ i.category || '–' }}</dd>
          <dt>View status</dt><dd>{{ auth.label('viewState', i.viewState) }}</dd>
          <dt>Reporter</dt><dd>{{ i.reporter.username }}</dd>
          <dt>Assigned to</dt><dd>{{ i.handler?.username || '–' }}</dd>
          <dt>Priority</dt><dd>{{ auth.label('priority', i.priority) }}</dd>
          <dt>Severity</dt><dd>{{ auth.label('severity', i.severity) }}</dd>
          <dt>Reproducibility</dt><dd>{{ auth.label('reproducibility', i.reproducibility) }}</dd>
        </dl>
        <dl class="kv">
          <dt>Status</dt><dd><span class="chip s{{ i.status }}">{{ auth.label('status', i.status) }}</span></dd>
          <dt>Resolution</dt><dd>{{ auth.label('resolution', i.resolution) }} @if (i.duplicateId) { <span>of <a [routerLink]="['/issues', i.duplicateId]">{{ i.duplicateId }}</a></span> }</dd>
          <dt>Platform / OS</dt><dd>{{ i.platform }} {{ i.os }} {{ i.osBuild }}</dd>
          <dt>Product version</dt><dd>{{ i.version }} {{ i.build }}</dd>
          <dt>Target version</dt><dd>{{ i.targetVersion || '–' }}</dd>
          <dt>Fixed in</dt><dd>{{ i.fixedInVersion || '–' }}</dd>
          <dt>Submitted</dt><dd>{{ i.dateSubmitted | date:'yyyy-MM-dd HH:mm' }}</dd>
          <dt>Last updated</dt><dd>{{ i.lastUpdated | date:'yyyy-MM-dd HH:mm' }}</dd>
        </dl>
      </div>
      <h3 style="margin-top:14px">Tags</h3>
      @for (t of i.tags; track t) { <a class="tag" [routerLink]="['/issues']" [queryParams]="{tag: t}">{{ t }}</a> } @empty { <span class="muted">none</span> }
      @if (i.monitors.length) { <h3 style="margin-top:14px">Monitored by</h3><span>{{ monitorNames() }}</span> }
    </div>

    <div class="panel"><h2>Description</h2><div class="pre">{{ i.description }}</div>
      @if (i.stepsToReproduce) { <h2 style="margin-top:14px">Steps to reproduce</h2><div class="pre">{{ i.stepsToReproduce }}</div> }
      @if (i.additionalInformation) { <h2 style="margin-top:14px">Additional information</h2><div class="pre">{{ i.additionalInformation }}</div> }</div>

    @if (i.customFields.length) {
      <div class="panel"><h2>Custom fields</h2><dl class="kv">
        @for (f of i.customFields; track f.fieldId) { <dt>{{ f.name }}</dt><dd class="pre">{{ f.value ? f.value.split('|').join(', ') : '–' }}</dd> }
      </dl></div>
    }

    <div class="panel"><h2>Attached files ({{ files.length }})</h2>
      @for (f of files; track f.id) {
        <div class="row" style="align-items:center;margin-bottom:4px">
          <a href="#" (click)="download(f); $event.preventDefault()">{{ f.filename }}</a>
          <span class="muted">{{ f.filesize / 1024 | number:'1.0-1' }} KB · {{ f.dateAdded | date:'yyyy-MM-dd' }}</span>
          @if (f.canDelete) { <button class="small danger" (click)="deleteFile(f)">delete</button> }
        </div>
      } @empty { <span class="muted">No files.</span> }
      @if (i.canAttach) { <div style="margin-top:10px"><input type="file" (change)="upload($event)" style="width:auto"> <span class="muted">max 5 MB</span></div> }
    </div>

    <div class="panel"><h2>Relationships</h2>
      @for (r of rels; track r.id) {
        <div class="row" style="align-items:center;margin-bottom:4px"><b>{{ r.typeLabel }}</b>
          <a [routerLink]="['/issues', r.otherIssueId]">{{ r.otherIssueId.toString().padStart(7,'0') }}</a>
          <span class="chip s{{ r.otherStatus }}">{{ auth.label('status', r.otherStatus) }}</span> <span>{{ r.otherSummary }}</span>
          @if (i.canUpdate) { <button class="small danger" (click)="deleteRel(r)">remove</button> }</div>
      } @empty { <span class="muted">None.</span> }
      @if (i.canUpdate) {
        <div class="row" style="margin-top:10px;align-items:center">
          <select style="width:auto" [(ngModel)]="relType">@for (o of auth.options('relationship'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select>
          <input style="width:120px" type="number" [(ngModel)]="relOther" placeholder="issue id"><button (click)="addRel()">Add</button>
        </div>
      }
    </div>

    @if (i.canChangeStatus) {
      <div class="panel"><h2>Change status / assign</h2>
        <div class="row">
          <div><label>Status</label><select [(ngModel)]="st.status">@for (o of auth.options('status'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
          <div><label>Resolution</label><select [(ngModel)]="st.resolution"><option [ngValue]="null">(auto)</option>@for (o of auth.options('resolution'); track o.value) { <option [ngValue]="o.value">{{ o.label }}</option> }</select></div>
          <div><label>Assign to</label><select [(ngModel)]="st.handlerId"><option [ngValue]="null">(unchanged)</option>@for (u of handlers; track u.id) { <option [ngValue]="u.id">{{ u.username }}</option> }</select></div>
          @if (st.resolution === 60) { <div><label>Duplicate of #</label><input type="number" [(ngModel)]="st.duplicateId" style="width:110px"></div> }
          <div class="grow"><label>Note (optional)</label><input [(ngModel)]="st.note"></div>
          <div><label>&nbsp;</label><button class="primary" (click)="applyStatus()">Apply</button></div>
        </div>
      </div>
    }

    <div class="panel"><h2>Notes ({{ notes.length }})</h2>
      @for (n of notes; track n.id) {
        <div class="note" [class.private]="n.viewState === 50">
          <div class="muted"><b style="color:var(--text)">{{ n.reporter.username }}</b> · {{ n.dateSubmitted | date:'yyyy-MM-dd HH:mm' }}
            @if (n.viewState === 50) { · 🔒 private }
            @if (n.canEdit) { · <a href="#" (click)="editNote(n); $event.preventDefault()">edit</a> · <a href="#" (click)="deleteNote(n); $event.preventDefault()">delete</a> }</div>
          @if (editingNote?.id === n.id) {
            <textarea [(ngModel)]="editText" rows="3"></textarea>
            <button class="small primary" (click)="saveNote()">Save</button> <button class="small" (click)="editingNote = null">Cancel</button>
          } @else { <div class="pre">{{ n.text }}</div> }
        </div>
      } @empty { <span class="muted">No notes yet.</span> }
      @if (i.canAddNote) {
        <div style="margin-top:12px"><label>Add note</label><textarea [(ngModel)]="newNote" rows="3"></textarea>
          <div class="row" style="margin-top:6px;align-items:center">
            @if (isDev()) { <label style="display:flex;gap:5px;align-items:center;margin:0;font-weight:400"><input type="checkbox" [(ngModel)]="notePrivate"> private</label> }
            <button class="primary" (click)="addNote()" [disabled]="!newNote.trim()">Add note</button></div></div>
      }
    </div>

    <div class="panel"><h2>History <button class="small" (click)="toggleHistory()">{{ showHistory ? 'hide' : 'show' }}</button></h2>
      @if (showHistory) {
        <table><thead><tr><th>Date</th><th>User</th><th>Field</th><th>Change</th></tr></thead><tbody>
          @for (h of hist; track h.id) {
            <tr><td class="nowrap">{{ h.date | date:'yyyy-MM-dd HH:mm' }}</td><td>{{ h.user?.username }}</td>
              <td>{{ historyField(h) }}</td><td>{{ historyChange(h) }}</td></tr>
          }
        </tbody></table>
      }
    </div>
  }`
})
export class IssueViewComponent implements OnInit {
  @Input() id!: string;
  auth = inject(AuthService);
  private api = inject(ApiService);
  private router = inject(Router);

  i: IssueView | null = null; error = '';
  notes: NoteView[] = []; files: AttachmentView[] = []; rels: RelationshipView[] = []; hist: HistoryView[] = [];
  handlers: UserRef[] = []; showHistory = false;
  newNote = ''; notePrivate = false; editingNote: { id: number; text: string; viewState: number } | null = null;
  editText = '';
  relType = 1; relOther: number | null = null;
  st: any = { status: 10, resolution: null, handlerId: null, duplicateId: null, note: '' };

  ngOnInit() { this.load(); }

  isDev() { return this.i?.canChangeStatus ?? false; }
  monitorNames() { return this.i!.monitors.map(m => m.username).join(', '); }

  load() {
    const id = Number(this.id);
    this.api.issue(id).subscribe({
      next: i => {
        this.i = i; this.st.status = i.status;
        if (i.canChangeStatus && !this.handlers.length) this.api.assignable(i.projectId).subscribe(u => this.handlers = u);
      },
      error: e => this.error = errMsg(e)
    });
    this.api.notes(id).subscribe(n => this.notes = n);
    this.api.attachments(id).subscribe(f => this.files = f);
    this.api.relationships(id).subscribe(r => this.rels = r);
    if (this.showHistory) this.api.history(id).subscribe(h => this.hist = h);
  }

  private fail = (e: any) => this.error = errMsg(e);

  toggleMonitor() {
    const call = this.i!.monitoring ? this.api.unmonitor(this.i!.id) : this.api.monitor(this.i!.id);
    call.subscribe({ next: () => this.load(), error: this.fail });
  }
  remove() {
    if (!confirm('Delete this issue permanently?')) return;
    this.api.deleteIssue(this.i!.id).subscribe({ next: () => this.router.navigate(['/issues']), error: this.fail });
  }
  applyStatus() {
    this.api.changeStatus(this.i!.id, { ...this.st, duplicateId: this.st.resolution === 60 ? this.st.duplicateId : null })
      .subscribe({ next: () => { this.st.note = ''; this.error = ''; this.load(); }, error: this.fail });
  }
  addNote() {
    this.api.addNote(this.i!.id, this.newNote, this.notePrivate ? 50 : 10)
      .subscribe({ next: () => { this.newNote = ''; this.notePrivate = false; this.load(); }, error: this.fail });
  }
  editNote(n: NoteView) { this.editingNote = { id: n.id, text: n.text, viewState: n.viewState }; this.editText = n.text; }
  saveNote() {
    const e = this.editingNote!;
    this.api.updateNote(e.id, this.editText, e.viewState).subscribe({ next: () => { this.editingNote = null; this.load(); }, error: this.fail });
  }
  deleteNote(n: NoteView) { if (confirm('Delete this note?')) this.api.deleteNote(n.id).subscribe({ next: () => this.load(), error: this.fail }); }

  upload(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const f = input.files?.[0];
    if (!f) return;
    this.api.upload(this.i!.id, f).subscribe({ next: () => { input.value = ''; this.load(); }, error: this.fail });
  }
  download(f: AttachmentView) {
    this.api.downloadAttachment(f.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a'); a.href = url; a.download = f.filename; a.click();
        URL.revokeObjectURL(url);
      }, error: this.fail
    });
  }
  deleteFile(f: AttachmentView) { if (confirm('Delete ' + f.filename + '?')) this.api.deleteAttachment(f.id).subscribe({ next: () => this.load(), error: this.fail }); }

  addRel() {
    if (!this.relOther) return;
    this.api.addRelationship(this.i!.id, this.relType, this.relOther).subscribe({ next: () => { this.relOther = null; this.error = ''; this.load(); }, error: this.fail });
  }
  deleteRel(r: RelationshipView) { this.api.deleteRelationship(r.id).subscribe({ next: () => this.load(), error: this.fail }); }

  toggleHistory() {
    this.showHistory = !this.showHistory;
    if (this.showHistory) this.api.history(this.i!.id).subscribe(h => this.hist = h);
  }
  historyField(h: HistoryView): string {
    const t: Record<number, string> = { 1: 'New issue', 2: 'Note added', 3: 'Note changed', 9: 'File added', 10: 'File deleted',
      12: 'Monitor', 13: 'Monitor removed', 18: 'Relationship added', 19: 'Relationship removed', 25: 'Tag attached', 26: 'Tag detached' };
    return h.type === 0 ? h.field.replace(/_/g, ' ') : (t[h.type] ?? 'Change');
  }
  historyChange(h: HistoryView): string {
    if (h.type === 0 && ['description', 'steps_to_reproduce', 'additional_information'].includes(h.field)) return 'updated';
    if (h.oldValue && h.newValue) return h.oldValue + ' → ' + h.newValue;
    return h.newValue ?? h.oldValue ?? '';
  }
}
