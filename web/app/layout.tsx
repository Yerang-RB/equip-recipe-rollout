import localFont from "next/font/local";
import "./globals.css";

// 본문·UI(설계 §10 확정값). 파일은 pretendard@1.3.9 배포본 그대로다(출처: fonts/README.md).
const Pretendard = localFont({
  src: [
    { path: "./fonts/Pretendard-Regular.woff2", weight: "400", style: "normal" },
    { path: "./fonts/Pretendard-Medium.woff2", weight: "500", style: "normal" },
    { path: "./fonts/Pretendard-SemiBold.woff2", weight: "600", style: "normal" },
    { path: "./fonts/Pretendard-Bold.woff2", weight: "700", style: "normal" },
  ],
  declarations: [{ prop: "font-family", value: "Pretendard" }],
  variable: "--font-sans",
  display: "swap",
  preload: false,
});

// 설비 코드·잡 ID·파라미터·로그. 원본의 수정본이라 원래 이름을 쓰지 않는다(fonts/NOTICE.md).
// 패밀리명의 공백 때문에 빌드는 webpack이다 — Turbopack은 `--font-mono`를 declarations가 아니라
// 이 상수 이름으로 채워 @font-face("Rollout Mono")와 어긋난다(2026-09-17 빌드 CSS 실측).
const RolloutMono = localFont({
  src: [
    { path: "./fonts/RolloutMono-Regular.woff2", weight: "400", style: "normal" },
    { path: "./fonts/RolloutMono-Medium.woff2", weight: "500", style: "normal" },
    { path: "./fonts/RolloutMono-Bold.woff2", weight: "700", style: "normal" },
  ],
  declarations: [{ prop: "font-family", value: "Rollout Mono" }],
  variable: "--font-mono",
  display: "swap",
  preload: false,
});

export const metadata = {
  title: { template: "%s | 레시피 배포", default: "레시피 배포" },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={`${Pretendard.variable} ${RolloutMono.variable}`}>
      <body>
        <header className="top">
          <a href="/equipment" className="brand">레시피 배포</a>
          <nav>
            <a href="/equipment">설비</a>
            <a href="/recipes">레시피</a>
            <a href="/jobs/new">배포</a>
            <a href="/history">이력</a>
            <a href="/audit">감사</a>
          </nav>
        </header>
        <main className="page">{children}</main>
      </body>
    </html>
  );
}
