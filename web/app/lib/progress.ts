import type { Job, JobSnapshot, JobStatus, TaskStatus } from "./types.ts";

const TERMINAL_TASK: TaskStatus[] = ["SUCCEEDED", "FAILED", "CANCELLED"];

/** 잡 진행률 집계. `done`은 종료된 task 수(성공·실패·취소), `percent`는 그 비율의 내림 정수. */
export function progress(tasks: { status: TaskStatus }[]) {
  const count = (s: TaskStatus) => tasks.filter((t) => t.status === s).length;
  const total = tasks.length;
  const done = tasks.filter((t) => TERMINAL_TASK.includes(t.status)).length;
  return {
    total,
    done,
    succeeded: count("SUCCEEDED"),
    failed: count("FAILED"),
    cancelled: count("CANCELLED"),
    running: count("CLAIMED"),
    pending: count("PENDING"),
    percent: total === 0 ? 0 : Math.floor((done * 100) / total),
  };
}

/** 잡이 더 변하지 않는 상태인가 — 실시간 구독을 다시 붙일지 판단한다. */
export function isJobSettled(status: JobStatus): boolean {
  return status === "SUCCEEDED" || status === "PARTIAL" || status === "FAILED";
}

const LABELS: Record<string, string> = {
  // 잡
  RUNNING: "진행 중",
  PARTIAL: "일부 실패",
  // task
  PENDING: "대기",
  CLAIMED: "적용 중",
  SUCCEEDED: "성공",
  FAILED: "실패",
  CANCELLED: "취소",
  // 설비 (RUNNING은 잡과 겹쳐 equipmentLabel에서 따로 준다)
  STOPPED: "정지",
  MAINTENANCE: "점검",
  // 레시피
  DRAFT: "초안",
  ACTIVE: "승인됨",
  RETIRED: "폐기",
  // 이력·감사
  APPLIED: "적용",
  NO_CHANGE: "변경 없음",
  OK: "성공",
  DENIED: "거부",
  ERROR: "오류",
  // 역할
  OPERATOR: "작업자",
  APPROVER: "승인자",
  ADMIN: "관리자",
};

/** 상태 코드의 화면 표기. 모르는 코드는 그대로 보여 준다(계약이 늘어도 빈칸이 되지 않게). */
export function statusLabel(code: string): string {
  return LABELS[code] ?? code;
}

export function equipmentLabel(code: string): string {
  return code === "RUNNING" ? "가동" : statusLabel(code);
}

/**
 * WS 스냅샷을 화면의 잡에 합친다. 스냅샷 task에는 id가 없어 설비 코드로 맞추고,
 * 스냅샷에 없는 task는 그대로 둔다. 다른 잡의 스냅샷이면 원래 잡을 돌려준다.
 */
export function mergeSnapshot(job: Job, snap: JobSnapshot): Job {
  if (snap.jobId !== job.id) return job;
  const byCode = new Map(snap.tasks.map((t) => [t.equipmentCode, t]));
  return {
    ...job,
    status: snap.status ?? job.status,
    tasks: (job.tasks ?? []).map((t) => {
      const s = byCode.get(t.equipmentCode);
      return s ? { ...t, status: s.status, attempt: s.attempt, lastError: s.lastError } : t;
    }),
  };
}
