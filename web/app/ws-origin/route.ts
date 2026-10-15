// WebSocket 원점은 런타임 env로 준다(빌드에 굳지 않게). rewrite는 업그레이드를 넘기지 못해
// 브라우저가 api에 직접 붙는다 — 세션 쿠키는 포트를 가리지 않고, api는 Origin을 APP_WEB_ORIGIN 하나로 막는다.
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ origin: process.env.WS_ORIGIN ?? "ws://localhost:8080" });
}
