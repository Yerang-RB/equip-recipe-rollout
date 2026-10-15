// 계약 `backend/src/main/resources/openapi.yml`의 응답 스키마를 옮긴 것. 정본은 그 파일이다.

export type Role = "OPERATOR" | "APPROVER" | "ADMIN";
export type Me = { username: string; displayName: string; role: Role };

export type EquipmentStatus = "RUNNING" | "STOPPED" | "MAINTENANCE";
export type Equipment = {
  id: number;
  code: string;
  consoleUrl: string;
  model: string;
  status: EquipmentStatus;
  lastApplied?: string | null;
};

export type RecipeStatus = "DRAFT" | "ACTIVE" | "RETIRED";
export type Recipe = {
  id: number;
  partNo: string;
  version: number;
  status: RecipeStatus;
  params: Record<string, string>;
  createdBy: number;
  approvedBy?: number | null;
  createdAt?: string;
};

export type TaskStatus = "PENDING" | "CLAIMED" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type Task = {
  id: number;
  equipmentId: number;
  equipmentCode: string;
  status: TaskStatus;
  attempt: number;
  lastError?: string | null;
};

export type JobStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED";
export type Job = {
  id: number;
  recipeId: number;
  partNo?: string;
  version?: number;
  status: JobStatus;
  requestedBy: number;
  createdAt?: string;
  tasks?: Task[];
};

/** WebSocket 스냅샷(`Realtime.kt` `JobSnapshotRepository.of`). task에 id가 없어 설비 코드로 맞춘다. */
export type JobSnapshot = {
  jobId: number;
  status: JobStatus | null;
  tasks: { equipmentCode: string; status: TaskStatus; attempt: number; lastError: string | null }[];
};

export type History = {
  id: number;
  equipmentId: number;
  equipmentCode: string;
  partNo: string;
  version: number;
  result: "APPLIED" | "NO_CHANGE";
  appliedAt: string;
  appliedBy: number;
};

export type Audit = {
  id: number;
  at: string;
  actorId?: number | null;
  actorName: string;
  action: string;
  targetType: string;
  targetId?: number | null;
  result: "OK" | "DENIED" | "ERROR";
};
