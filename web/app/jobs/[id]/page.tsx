"use client";

import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "../../lib/api.ts";
import { isJobSettled, mergeSnapshot, progress, statusLabel } from "../../lib/progress.ts";
import type { Job, JobSnapshot } from "../../lib/types.ts";

export default function JobPage() {
  const { id } = useParams<{ id: string }>();
  const [job, setJob] = useState<Job | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);

  function load() {
    api<Job>(`/api/jobs/${id}`).then(setJob, (e: Error) => setError(e.message));
  }

  useEffect(load, [id]);

  // 실시간 구독: 연결 직후 스냅샷 한 번, 이후 진행마다 스냅샷. 끊기면 한 번만 다시 붙는다.
  useEffect(() => {
    let socket: WebSocket | null = null;
    let reconnects = 0;
    let closed = false;
    const connect = (origin: string) => {
      socket = new WebSocket(`${origin}/api/ws/jobs/${id}`);
      socket.onopen = () => setLive(true);
      socket.onmessage = (m) => {
        const snap = JSON.parse(m.data) as JobSnapshot;
        setJob((j) => (j ? mergeSnapshot(j, snap) : j));
      };
      socket.onclose = () => {
        setLive(false);
        if (!closed && reconnects < 1) {
          reconnects++;
          load();
          connect(origin);
        }
      };
    };
    fetch("/ws-origin")
      .then((r) => r.json())
      .then((b: { origin: string }) => { if (!closed) connect(b.origin); });
    return () => {
      closed = true;
      socket?.close();
    };
  }, [id]);

  async function act(action: "cancel" | "retry") {
    try {
      setJob(await api<Job>(`/api/jobs/${id}/${action}`, { method: "POST" }));
      setError(null);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  const tasks = job?.tasks ?? [];
  const p = progress(tasks);

  return (
    <>
      <h1>배포 잡 <span className="mono">#{id}</span></h1>
      {error && <p className="error" role="alert">{error}</p>}
      {job && (
        <>
          <div className="panel">
            <p>
              <span className={`badge ${job.status}`}>{statusLabel(job.status)}</span>{" "}
              <span className="mono">{job.partNo} v{job.version}</span>{" "}
              <span className="muted">{live ? "실시간 연결됨" : "실시간 끊김"}</span>
            </p>
            <div className="bar" aria-label={`진행률 ${p.percent}%`}><span style={{ width: `${p.percent}%` }} /></div>
            <p className="muted">
              {p.done}/{p.total} 종료 · 성공 {p.succeeded} · 실패 {p.failed} · 적용 중 {p.running} · 대기 {p.pending}
            </p>
          </div>
          <div className="actions">
            <button className="secondary" onClick={() => act("cancel")} disabled={isJobSettled(job.status) || p.pending === 0}>대기 중인 설비 취소</button>
            <button onClick={() => act("retry")} disabled={p.failed + p.cancelled === 0}>실패·취소된 설비 재시도</button>
          </div>
          <table>
            <thead><tr><th>설비 코드</th><th>상태</th><th>시도</th><th>오류</th></tr></thead>
            <tbody>
              {tasks.map((t) => (
                <tr key={t.id}>
                  <td className="mono">{t.equipmentCode}</td>
                  <td><span className={`badge ${t.status}`}>{statusLabel(t.status)}</span></td>
                  <td className="mono">{t.attempt}</td>
                  <td className="mono">{t.lastError ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}
