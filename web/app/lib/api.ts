"use client";

import { useEffect, useState } from "react";
import type { Me } from "./types.ts";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** 같은 출처 `/api` 호출. 401이면 로그인 화면으로 보낸다. 오류 본문은 계약의 `{code, message}` 하나다. */
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { ...(init?.body ? { "Content-Type": "application/json" } : {}), ...init?.headers },
  });
  if (res.status === 401 && !path.startsWith("/api/auth/login")) {
    window.location.href = "/login";
  }
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, body?.code ?? "UNKNOWN", errorMessage(body?.code));
  return body as T;
}

const MESSAGES: Record<string, string> = {
  UNAUTHENTICATED: "아이디 또는 비밀번호가 맞지 않습니다.",
  FORBIDDEN: "이 작업을 할 권한이 없습니다.",
  SELF_APPROVAL_FORBIDDEN: "자기가 만든 레시피는 승인할 수 없습니다.",
  NOT_FOUND: "대상을 찾을 수 없습니다.",
  RECIPE_NOT_ACTIVE: "승인된 레시피만 배포할 수 있습니다.",
  INVALID_TRANSITION: "지금 상태에서는 할 수 없는 작업입니다.",
  INVALID_REQUEST: "입력값을 확인해 주세요.",
  CONSTRAINT_VIOLATION: "이미 존재하는 값과 중복됩니다.",
};

function errorMessage(code: string | undefined): string {
  return (code && MESSAGES[code]) ?? "요청을 처리하지 못했습니다.";
}

/** 현재 사용자. 로그인 전이면 api()가 로그인 화면으로 보낸다. */
export function useMe(): Me | null {
  const [me, setMe] = useState<Me | null>(null);
  useEffect(() => {
    api<Me>("/api/auth/me").then(setMe, () => {});
  }, []);
  return me;
}

/** 목록 하나를 불러온다. 실패는 오류 문구로 돌려준다. */
export function useLoad<T>(path: string | null): { data: T | null; error: string | null; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!path) return;
    api<T>(path).then(setData, (e: Error) => setError(e.message));
  }, [path, tick]);
  return { data, error, reload: () => setTick((n) => n + 1) };
}

export function formatAt(iso: string | undefined): string {
  if (!iso) return "";
  return new Date(iso).toLocaleString("ko-KR", { hour12: false });
}
