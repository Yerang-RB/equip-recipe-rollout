import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // 브라우저는 web만 본다. /api/*를 api로 넘겨 동일 출처를 만든다 — CORS가 필요 없다(불변 조건 16).
  // 값은 빌드·기동 시점의 서버 env다. 브라우저 번들에 굳는 공개 env 접두어는 쓰지 않는다(design-gates.sh).
  async rewrites() {
    const apiOrigin = process.env.API_ORIGIN ?? "http://127.0.0.1:8080";
    return [{ source: "/api/:path*", destination: `${apiOrigin}/api/:path*` }];
  },
};

export default nextConfig;
