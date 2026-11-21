export interface UserRef { id: number; username: string; realname: string; }
export interface UserView { id: number; username: string; realname: string; email: string; accessLevel: number;
  enabled: boolean; protectedUser: boolean; dateCreated: string; lastVisit: string | null; }
export interface LoginResponse { token: string; user: UserView; }
export interface ProjectView { id: number; name: string; status: number; enabled: boolean; viewState: number;
  description: string; myAccess: number; }
export interface CategoryView { id: number; name: string; defaultHandlerId: number | null; defaultHandlerName: string | null; }
export interface VersionView { id: number; version: string; description: string; released: boolean; obsolete: boolean; dateOrder: string; }
export interface MemberView { userId: number; username: string; realname: string; accessLevel: number; }
export interface TagView { id: number; name: string; description: string; }
export interface IssueSummary { id: number; projectId: number; projectName: string; summary: string; status: number;
  resolution: number; priority: number; severity: number; reporter: UserRef; handler: UserRef | null;
  category: string | null; dateSubmitted: string; lastUpdated: string; viewState: number; sticky: boolean; }
export interface IssueView {
  id: number; projectId: number; projectName: string; categoryId: number | null; category: string | null;
  summary: string; description: string; stepsToReproduce: string; additionalInformation: string;
  status: number; resolution: number; priority: number; severity: number; reproducibility: number;
  projection: number; eta: number; reporter: UserRef; handler: UserRef | null; duplicateId: number | null;
  os: string; osBuild: string; platform: string; version: string; fixedInVersion: string; targetVersion: string;
  build: string; viewState: number; sticky: boolean; dateSubmitted: string; lastUpdated: string;
  tags: string[]; monitors: UserRef[]; monitoring: boolean; canUpdate: boolean; canChangeStatus: boolean;
  canDelete: boolean; canAddNote: boolean; canAttach: boolean;
}
export type IssueForm = Partial<{
  projectId: number; categoryId: number | null; summary: string; description: string; stepsToReproduce: string;
  additionalInformation: string; priority: number; severity: number; reproducibility: number; status: number;
  resolution: number; projection: number; eta: number; handlerId: number | null; clearHandler: boolean;
  duplicateId: number; os: string; osBuild: string; platform: string; version: string; fixedInVersion: string;
  targetVersion: string; build: string; viewState: number; sticky: boolean; tags: string[];
}>;
export interface NoteView { id: number; reporter: UserRef; text: string; viewState: number; noteType: number;
  dateSubmitted: string; lastModified: string; canEdit: boolean; }
export interface AttachmentView { id: number; title: string; filename: string; filesize: number; fileType: string;
  dateAdded: string; canDelete: boolean; }
export interface HistoryView { id: number; user: UserRef | null; field: string; oldValue: string | null;
  newValue: string | null; type: number; date: string; }
export interface RelationshipView { id: number; type: number; typeLabel: string; otherIssueId: number;
  otherSummary: string; otherStatus: number; }
export interface NewsView { id: number; projectId: number | null; poster: UserRef; headline: string; body: string;
  viewState: number; announcement: boolean; datePosted: string; }
export interface PageResult<T> { items: T[]; total: number; page: number; size: number; }
export interface CountEntry { key: number; label: string; count: number; }
export interface SummaryView { total: number; byStatus: CountEntry[]; byPriority: CountEntry[]; bySeverity: CountEntry[];
  unassigned: number; open: number; }
export type Meta = Record<string, Record<string, string>>;

export interface IssueQuery {
  projectId?: number | null; status?: number[]; handlerId?: number | null; reporterId?: number | null;
  priority?: number | null; severity?: number | null; categoryId?: number | null; q?: string; tag?: string;
  hideClosed?: boolean; sortBy?: string; dir?: string; page?: number; size?: number;
}

export const ACCESS = { VIEWER: 10, REPORTER: 25, UPDATER: 40, DEVELOPER: 55, MANAGER: 70, ADMINISTRATOR: 90 };
