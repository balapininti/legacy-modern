import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { AdminControllerService, AuthControllerService, DataControllerService, MigrationControllerService, ReviewControllerService, TrackerControllerService } from '../api-client/services';

/** The generated client types every body as a loose map, because the controllers return maps. These say what is in them. */
export interface Me { username: string; roles: string[] }
export interface AuthConfig { oidc_issuer: string; oidc_client_id: string }
export interface Inventory {
  members_by_type: Record<string, number>;
  members_by_dialect: Record<string, number>;
  parse_status: Record<string, number>;
  programs: number;
  lines: number;
  edges_by_type: Record<string, number>;
  static_calls: { total: number; system_api: number; resolved_in_estate: number; resolved_pct: number };
  unresolved_dynamic_calls: number;
  jobs: number;
  job_steps: number;
  maps: number;
  map_fields: number;
  db2_tables: number;
  dead_code_candidates: number;
  dialect_features: Record<string, number>;
  parse_failures: { path: string; type: string; parse_error: string }[];
  unresolved_calls: { program: string; member: string; line: number; target_expr: string; reason: string }[];
  calls_to_missing_programs: { target: string; calls: number }[];
  missing_copybooks: { name: string; path: string }[];
  missing_procs: { proc: string; path: string }[];
  dead_code: { program_id: string; path: string }[];
  unknown_members: { path: string; classify_reason: string }[];
}
export interface ProgramRow { program_id: string; kind: string; member: string; line_start: number; line_end: number; metrics: string; stage: string }
export interface CallEdge { from_program: string; to_program: string; edge_type: string; call_mode: string | null; via: string | null; depth: number; member: string | null; line: number | null }
export interface DataItem { level: number; name: string; pic: string | null; usage: string | null; offset: number | null; length: number | null; occurs: number | null; redefines: string | null; member: string; line: number }
export interface Paragraph { name: string; section: string | null; line_start: number; line_end: number; complexity: number; reachable: boolean }
export interface ProgramArtifact { id: number; kind: string; status: string; reviewer: string | null; content: string; line_start: number | null; line_end: number | null }
export interface ProgramDetail extends ProgramRow {
  source: string[];
  paragraphs: Paragraph[];
  data_items: DataItem[];
  callers: CallEdge[];
  callees: CallEdge[];
  artifacts: ProgramArtifact[];
}
export interface GraphEdge { source: string; target: string; type: string | null; line: number | null; member: string | null }
export interface GraphData { root: string; nodes: string[]; edges: GraphEdge[] }
export interface SearchHit { kind: string; member: string | null; lineStart: number | null; lineEnd: number | null; snippet: string; score: number; artifactId: number | null; status: string | null; unit: string | null }
export interface SearchResult { mode: string; hits: SearchHit[] }
export interface Artifact { id: number; unit_kind: string; kind: string; status: string; reviewer: string | null; model: string; prompt_version: string; content: string; member: string | null; line_start: number | null; line_end: number | null; created_at: string }
export interface Link { id: number; legacy: string; granularity: string; confidence: number; source: string; status: string; confirmed_by: string | null; evidence: string; modern_kind: string; modern_name: string; repo: string; path: string; line_start: number | null; line_end: number | null }
export interface Deviation { id: number; legacy_ref: string; modern_ref: string; change: string; reason: string; status: string; approved_by: string | null; comparison_rule: string; created_at: string }
export interface GapRule { artifact_id: number; rule_id: string; statement: string; artifact_status: string; line_start: number | null; line_end: number | null; coverage: string; modern_path: string | null; modern_name: string | null }
export interface GapRow { program: string; member: string; status: string; rules_total: number; rules_uncovered: number; modern_locations: { kind: string; name: string; path: string; line_start: number | null; line_end: number | null }[]; rules: GapRule[] }
export interface TrackerRow { program_id: string; kind: string; member: string; stage: string; owner: string | null; test_count: number; tests_passing: number; target_refs: string }
export interface Tracker { stages: string[]; programs: TrackerRow[] }
export interface Capture { id: number; subject_kind: string; subject_id: string; status: string; source: string; created_at: string; fixtures: number }
export interface Fixture { id: number; name: string; input: string; expected: string }
export interface UserRow { id: number; username: string; enabled: boolean; roles: string }
export interface TokenRow { id: number; username: string; name: string; created_at: string; last_used_at: string | null; revoked_at: string | null }
export interface AuditRow { ts: string; user: string; action: string; tool: string | null; params_hash: string | null; latency_ms: number | null; outcome: string | null }
export interface LlmRun { id: number; model: string; units: number; tokens_in: number; tokens_out: number; cost_usd: number; status: string; started_at: string; ended_at: string | null }
export interface JobRow { id: number; job_name: string; status: string; exit_code: string; start_time: string; end_time: string | null }
export interface Jobs { running: string | null; recent: JobRow[] }
export interface DoctorCheck { check: string; status: 'PASS' | 'FAIL' | 'SKIP'; detail: string }
export interface DoctorResult { ok: boolean; checks: DoctorCheck[] }

export const ROLES = ['admin', 'developer', 'sme-reviewer'] as const;

export interface ApiErrorInfo { code: string; message: string; hint: string; status: number }

/** The API's own words whenever it sent any: the same code, message and hint shape as the MCP tools. */
export function toApiError(e: unknown): ApiErrorInfo {
  if (e instanceof HttpErrorResponse) {
    const body = e.error?.error;
    if (body?.message) return { code: body.code ?? String(e.status), message: body.message, hint: body.hint ?? '', status: e.status };
    if (e.status === 0) return { code: 'UNREACHABLE', message: 'the API is not answering', hint: 'check that it is running', status: 0 };
    return { code: String(e.status), message: `the request failed with status ${e.status}`, hint: '', status: e.status };
  }
  return { code: 'CLIENT', message: e instanceof Error ? e.message : String(e), hint: '', status: -1 };
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly data = inject(DataControllerService);
  private readonly auth = inject(AuthControllerService);
  private readonly admin = inject(AdminControllerService);
  private readonly migration = inject(MigrationControllerService);
  private readonly review = inject(ReviewControllerService);
  private readonly trackerApi = inject(TrackerControllerService);

  authConfig = () => this.auth.authConfig() as unknown as Promise<AuthConfig>;
  login = (username: string, password: string) => this.auth.login({ body: { username, password } }) as unknown as Promise<{ token: string; username: string }>;
  logout = () => this.auth.logout();
  me = () => this.auth.me() as unknown as Promise<Me>;

  inventory = () => this.data.inventory() as unknown as Promise<Inventory>;
  programs = (q?: string, limit = 1000) => this.data.programs({ q: q || undefined, limit }) as unknown as Promise<ProgramRow[]>;
  program = (name: string) => this.data.program({ name }) as unknown as Promise<ProgramDetail>;
  jobNames = () => this.data.jobNames() as unknown as Promise<{ name: string; member: string }[]>;
  callGraph = (program: string, depth: number, direction: string) => this.data.callGraph({ program, depth, direction }) as unknown as Promise<GraphData>;
  jobGraph = (job: string) => this.data.jobGraph({ job }) as unknown as Promise<GraphData>;
  lineage = (item: string, program: string | undefined, depth: number) => this.data.lineage({ item, program: program || undefined, depth }) as unknown as Promise<GraphData>;
  search = (q: string, kinds?: string[], limit = 50) => this.data.search({ q, kinds: kinds?.length ? kinds : undefined, limit }) as unknown as Promise<SearchResult>;
  gapReport = (program?: string) => this.data.gap({ program: program || undefined }) as unknown as Promise<GapRow[]>;
  tracker = () => this.data.tracker() as unknown as Promise<Tracker>;
  captures = () => this.data.captures() as unknown as Promise<Capture[]>;
  fixtures = (id: number) => this.data.fixtures({ id }) as unknown as Promise<Fixture[]>;

  artifacts = (status: string | undefined, kind: string | undefined, limit: number, offset: number) => this.review.artifacts({ status, kind, limit, offset }) as unknown as Promise<Artifact[]>;
  setArtifactStatus = (id: number, status: string) => this.review.setStatus({ id, body: { status } });
  editArtifact = (id: number, content: unknown) => this.review.edit({ id, body: content as never });
  confirmCoverage = (artifactId: number, ruleId: string, status: string) => this.review.confirm({ body: { artifactId, ruleId, status } });

  links = (status?: string) => this.migration.links({ status }) as unknown as Promise<Link[]>;
  confirmLink = (id: number) => this.migration.confirm1({ id });
  rejectLink = (id: number) => this.migration.reject({ id });
  deviations = (status?: string, program?: string) => this.migration.deviations({ status, program: program || undefined }) as unknown as Promise<Deviation[]>;
  approveDeviation = (id: number) => this.migration.approve({ id });
  rejectDeviation = (id: number) => this.migration.rejectDeviation({ id });

  moveStage = (program: string, stage: string | undefined, owner: string | undefined) => this.trackerApi.stage({ program, body: { stage, owner } });
  setImplementation = (program: string, status: string, note?: string) => this.trackerApi.implementation({ program, body: { status, note } });

  getConfig = () => this.admin.getConfig() as unknown as Promise<{ path: string; yaml: string }>;
  doctor = (yaml: string) => this.admin.doctor({ body: { yaml } }) as unknown as Promise<DoctorResult>;
  saveConfig = (yaml: string) => this.admin.saveConfig({ body: { yaml } }) as unknown as Promise<{ saved: string }>;
  users = () => this.admin.users() as unknown as Promise<UserRow[]>;
  addUser = (username: string, roles: string[]) => this.admin.addUser({ body: { username, roles } });
  setRoles = (id: number, roles: string[]) => this.admin.setRoles({ id, body: roles });
  tokens = () => this.admin.listTokens() as unknown as Promise<TokenRow[]>;
  newToken = (username: string, name: string) => this.admin.newToken({ body: { username, name } }) as unknown as Promise<{ token: string }>;
  revokeToken = (id: number) => this.admin.revoke({ id });
  audit = (limit: number, offset: number) => this.admin.audit({ limit, offset }) as unknown as Promise<AuditRow[]>;
  llmRuns = () => this.admin.llmRuns() as unknown as Promise<LlmRun[]>;
  jobs = () => this.admin.jobs() as unknown as Promise<Jobs>;
  runJob = (name: string) => this.admin.run({ name });
}

export function parseJson<T = unknown>(text: string | null | undefined, fallback: T): T {
  try {
    return text ? (JSON.parse(text) as T) : fallback;
  } catch {
    return fallback;
  }
}
