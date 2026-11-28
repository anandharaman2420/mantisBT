import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import * as M from './models';

/** Thin typed wrapper over the Spring Boot REST API (all paths below /api). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = '/api';

  // auth
  login(username: string, password: string) { return this.http.post<M.LoginResponse>(`${this.base}/auth/login`, { username, password }); }
  me() { return this.http.get<M.UserView>(`${this.base}/auth/me`); }
  changePassword(currentPassword: string, newPassword: string) {
    return this.http.post<{ message: string }>(`${this.base}/auth/change-password`, { currentPassword, newPassword });
  }
  setPreferences(emailEnabled: boolean) { return this.http.put<M.UserView>(`${this.base}/auth/preferences`, { emailEnabled }); }
  meta() { return this.http.get<M.Meta>(`${this.base}/meta`); }

  // projects
  projects() { return this.http.get<M.ProjectView[]>(`${this.base}/projects`); }
  createProject(p: any) { return this.http.post<M.ProjectView>(`${this.base}/projects`, p); }
  updateProject(id: number, p: any) { return this.http.put<M.ProjectView>(`${this.base}/projects/${id}`, p); }
  deleteProject(id: number) { return this.http.delete<void>(`${this.base}/projects/${id}`); }
  categories(pid: number) { return this.http.get<M.CategoryView[]>(`${this.base}/projects/${pid}/categories`); }
  addCategory(pid: number, c: any) { return this.http.post<M.CategoryView>(`${this.base}/projects/${pid}/categories`, c); }
  updateCategory(cid: number, c: any) { return this.http.put<M.CategoryView>(`${this.base}/projects/categories/${cid}`, c); }
  deleteCategory(cid: number) { return this.http.delete<void>(`${this.base}/projects/categories/${cid}`); }
  versions(pid: number) { return this.http.get<M.VersionView[]>(`${this.base}/projects/${pid}/versions`); }
  addVersion(pid: number, v: any) { return this.http.post<M.VersionView>(`${this.base}/projects/${pid}/versions`, v); }
  updateVersion(vid: number, v: any) { return this.http.put<M.VersionView>(`${this.base}/projects/versions/${vid}`, v); }
  deleteVersion(vid: number) { return this.http.delete<void>(`${this.base}/projects/versions/${vid}`); }
  members(pid: number) { return this.http.get<M.MemberView[]>(`${this.base}/projects/${pid}/members`); }
  setMember(pid: number, userId: number, accessLevel: number) { return this.http.put<M.MemberView>(`${this.base}/projects/${pid}/members`, { userId, accessLevel }); }
  removeMember(pid: number, userId: number) { return this.http.delete<void>(`${this.base}/projects/${pid}/members/${userId}`); }

  // users
  users() { return this.http.get<M.UserView[]>(`${this.base}/users`); }
  assignable(projectId: number) { return this.http.get<M.UserRef[]>(`${this.base}/users/assignable`, { params: { projectId } }); }
  userLookup() { return this.http.get<M.UserRef[]>(`${this.base}/users/lookup`); }
  createUser(u: any) { return this.http.post<M.UserView>(`${this.base}/users`, u); }
  updateUser(id: number, u: any) { return this.http.put<M.UserView>(`${this.base}/users/${id}`, u); }
  deleteUser(id: number) { return this.http.delete<void>(`${this.base}/users/${id}`); }

  // issues
  private buildIssueParams(q: M.IssueQuery): HttpParams {
    let p = new HttpParams();
    const set = (k: string, v: unknown) => { if (v !== null && v !== undefined && v !== '') p = p.append(k, String(v)); };
    set('projectId', q.projectId); set('handlerId', q.handlerId); set('reporterId', q.reporterId);
    set('priority', q.priority); set('severity', q.severity); set('categoryId', q.categoryId);
    set('q', q.q); set('tag', q.tag); set('hideClosed', q.hideClosed); set('sortBy', q.sortBy);
    set('dir', q.dir);
    if (q.page !== undefined) set('page', q.page);
    if (q.size !== undefined) set('size', q.size);
    (q.status ?? []).forEach(s => set('status', s));
    return p;
  }

  issues(q: M.IssueQuery): Observable<M.PageResult<M.IssueSummary>> {
    return this.http.get<M.PageResult<M.IssueSummary>>(`${this.base}/issues`, { params: this.buildIssueParams(q) });
  }

  exportCsv(q: M.IssueQuery): Observable<Blob> {
    return this.http.get(`${this.base}/issues/export/csv`, { params: this.buildIssueParams(q), responseType: 'blob' });
  }

  exportExcel(q: M.IssueQuery): Observable<Blob> {
    return this.http.get(`${this.base}/issues/export/excel`, { params: this.buildIssueParams(q), responseType: 'blob' });
  }

  // saved filters
  savedFilters(projectId?: number | null): Observable<M.SavedFilterView[]> {
    let p = new HttpParams();
    if (projectId) p = p.set('projectId', projectId);
    return this.http.get<M.SavedFilterView[]>(`${this.base}/filters`, { params: p });
  }
  createFilter(f: M.SavedFilterForm): Observable<M.SavedFilterView> { return this.http.post<M.SavedFilterView>(`${this.base}/filters`, f); }
  updateFilter(id: number, f: M.SavedFilterForm): Observable<M.SavedFilterView> { return this.http.put<M.SavedFilterView>(`${this.base}/filters/${id}`, f); }
  deleteFilter(id: number): Observable<void> { return this.http.delete<void>(`${this.base}/filters/${id}`); }
  issue(id: number) { return this.http.get<M.IssueView>(`${this.base}/issues/${id}`); }
  createIssue(f: M.IssueForm) { return this.http.post<M.IssueView>(`${this.base}/issues`, f); }
  updateIssue(id: number, f: M.IssueForm) { return this.http.put<M.IssueView>(`${this.base}/issues/${id}`, f); }
  changeStatus(id: number, c: any) { return this.http.post<M.IssueView>(`${this.base}/issues/${id}/status`, c); }
  deleteIssue(id: number) { return this.http.delete<void>(`${this.base}/issues/${id}`); }
  summary(projectId: number | null) {
    return this.http.get<M.SummaryView>(`${this.base}/summary`, { params: projectId ? { projectId } : {} });
  }
  history(id: number) { return this.http.get<M.HistoryView[]>(`${this.base}/issues/${id}/history`); }
  notes(id: number) { return this.http.get<M.NoteView[]>(`${this.base}/issues/${id}/notes`); }
  addNote(id: number, text: string, viewState: number, timeMinutes?: number) { return this.http.post<M.NoteView>(`${this.base}/issues/${id}/notes`, { text, viewState, timeMinutes: timeMinutes || null }); }
  updateNote(noteId: number, text: string, viewState: number) { return this.http.put<M.NoteView>(`${this.base}/notes/${noteId}`, { text, viewState }); }
  deleteNote(noteId: number) { return this.http.delete<void>(`${this.base}/notes/${noteId}`); }
  attachments(id: number) { return this.http.get<M.AttachmentView[]>(`${this.base}/issues/${id}/attachments`); }
  upload(id: number, file: File) {
    const fd = new FormData(); fd.append('file', file);
    return this.http.post<M.AttachmentView>(`${this.base}/issues/${id}/attachments`, fd);
  }
  downloadAttachment(attId: number) { return this.http.get(`${this.base}/attachments/${attId}`, { responseType: 'blob' }); }
  deleteAttachment(attId: number) { return this.http.delete<void>(`${this.base}/attachments/${attId}`); }
  monitor(id: number) { return this.http.post<unknown>(`${this.base}/issues/${id}/monitors`, {}); }
  unmonitor(id: number) { return this.http.delete<unknown>(`${this.base}/issues/${id}/monitors`); }
  relationships(id: number) { return this.http.get<M.RelationshipView[]>(`${this.base}/issues/${id}/relationships`); }
  addRelationship(id: number, type: number, otherIssueId: number) { return this.http.post<M.RelationshipView>(`${this.base}/issues/${id}/relationships`, { type, otherIssueId }); }
  deleteRelationship(relId: number) { return this.http.delete<void>(`${this.base}/relationships/${relId}`); }

  // bulk project setup
  bulkCategories(pid: number, rows: { name: string; assignee: string }[]) { return this.http.post<M.ImportResult>(`${this.base}/projects/${pid}/categories/bulk`, { rows }); }
  bulkVersions(pid: number, rows: any[]) { return this.http.post<M.ImportResult>(`${this.base}/projects/${pid}/versions/bulk`, { rows }); }
  bulkMembers(pid: number, body: any) { return this.http.post<M.ImportResult>(`${this.base}/projects/${pid}/members/bulk`, body); }
  removeMembersBulk(pid: number, userIds: number[]) { return this.http.post<M.ImportResult>(`${this.base}/projects/${pid}/members/remove-bulk`, { userIds }); }
  copyFrom(pid: number, body: any) { return this.http.post<M.CopyResult>(`${this.base}/projects/${pid}/copy-from`, body); }

  // config / time tracking
  config() { return this.http.get<M.AppConfig>(`${this.base}/config`); }
  timeReport(projectId: number | null, from: string, to: string) {
    const params: any = { from, to }; if (projectId) params.projectId = projectId;
    return this.http.get<M.TimeReport>(`${this.base}/time-tracking`, { params });
  }

  // reports
  summaryReport(projectId: number | null) { return this.http.get<M.SummaryReport>(`${this.base}/reports/summary`, { params: projectId ? { projectId } : {} }); }

  // my view / roadmap / changelog
  myView(projectId: number | null) { return this.http.get<M.MyView>(`${this.base}/my-view`, { params: projectId ? { projectId } : {} }); }
  roadmap(projectId: number | null) { return this.http.get<M.ReleaseNotes>(`${this.base}/roadmap`, { params: projectId ? { projectId } : {} }); }
  changelog(projectId: number | null) { return this.http.get<M.ReleaseNotes>(`${this.base}/changelog`, { params: projectId ? { projectId } : {} }); }

  // bulk / move / copy
  bulk(req: any) { return this.http.post<M.BulkResult>(`${this.base}/issues/bulk`, req); }
  moveIssue(id: number, projectId: number) { return this.http.post<M.IssueView>(`${this.base}/issues/${id}/move`, { projectId }); }
  copyIssue(id: number, body: { projectId: number | null; copyNotes: boolean; copyAttachments: boolean; linkToOriginal: boolean }) {
    return this.http.post<M.IssueView>(`${this.base}/issues/${id}/copy`, body);
  }

  // custom fields
  customFields() { return this.http.get<M.CustomFieldView[]>(`${this.base}/custom-fields`); }
  createCustomField(f: any) { return this.http.post<M.CustomFieldView>(`${this.base}/custom-fields`, f); }
  updateCustomField(id: number, f: any) { return this.http.put<M.CustomFieldView>(`${this.base}/custom-fields/${id}`, f); }
  deleteCustomField(id: number) { return this.http.delete<void>(`${this.base}/custom-fields/${id}`); }
  linkedFields(pid: number) { return this.http.get<M.CustomFieldView[]>(`${this.base}/projects/${pid}/custom-field-links`); }
  availableFields(pid: number) { return this.http.get<M.CustomFieldView[]>(`${this.base}/projects/${pid}/custom-field-links/available`); }
  linkField(pid: number, fieldId: number) { return this.http.put<void>(`${this.base}/projects/${pid}/custom-field-links`, { fieldId }); }
  unlinkField(pid: number, fieldId: number) { return this.http.delete<void>(`${this.base}/projects/${pid}/custom-field-links/${fieldId}`); }
  reportFields(pid: number) { return this.http.get<M.IssueFieldValue[]>(`${this.base}/projects/${pid}/custom-fields`); }

  // tags / news
  tags() { return this.http.get<M.TagView[]>(`${this.base}/tags`); }
  news(projectId: number | null) { return this.http.get<M.NewsView[]>(`${this.base}/news`, { params: projectId ? { projectId } : {} }); }
  addNews(n: any) { return this.http.post<M.NewsView>(`${this.base}/news`, n); }
  deleteNews(id: number) { return this.http.delete<void>(`${this.base}/news/${id}`); }
}
