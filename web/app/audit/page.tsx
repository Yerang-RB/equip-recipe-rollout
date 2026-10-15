"use client";

import { formatAt, useLoad, useMe } from "../lib/api.ts";
import { statusLabel } from "../lib/progress.ts";
import type { Audit } from "../lib/types.ts";

export default function AuditPage() {
  const me = useMe();
  // 조회 권한은 APPROVER·ADMIN(서버가 강제). 작업자에게는 요청하지 않고 안내만 보인다.
  const allowed = me?.role === "APPROVER" || me?.role === "ADMIN";
  const { data, error } = useLoad<Audit[]>(allowed ? "/api/audit" : null);
  return (
    <>
      <h1>감사 로그</h1>
      {me && !allowed && <p className="muted">승인자와 관리자만 볼 수 있습니다.</p>}
      {error && <p className="error">{error}</p>}
      {data && (
        <table>
          <thead><tr><th>시각</th><th>행위자</th><th>행위</th><th>대상</th><th>결과</th></tr></thead>
          <tbody>
            {data.map((a) => (
              <tr key={a.id}>
                <td>{formatAt(a.at)}</td>
                <td>{a.actorName}</td>
                <td className="mono">{a.action}</td>
                <td className="mono">{a.targetType}{a.targetId != null ? ` #${a.targetId}` : ""}</td>
                <td><span className={`badge ${a.result}`}>{statusLabel(a.result)}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
