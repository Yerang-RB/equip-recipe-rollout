"use client";

import { formatAt, useLoad } from "../lib/api.ts";
import { statusLabel } from "../lib/progress.ts";
import type { History } from "../lib/types.ts";

export default function HistoryPage() {
  const { data, error } = useLoad<History[]>("/api/history");
  return (
    <>
      <h1>적용 이력</h1>
      {error && <p className="error">{error}</p>}
      <ol className="timeline">
        {data?.map((h) => (
          <li key={h.id}>
            <span>{formatAt(h.appliedAt)}</span>
            <span className="mono">{h.equipmentCode}</span>
            <span className="mono">{h.partNo} v{h.version}</span>
            <span className={`badge ${h.result}`}>{statusLabel(h.result)}</span>
          </li>
        ))}
      </ol>
      {data?.length === 0 && <p className="muted">아직 적용된 레시피가 없습니다.</p>}
    </>
  );
}
