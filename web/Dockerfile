# 운영 화면(Next standalone). /api/* rewrite 대상은 빌드 때 next.config에 굳으므로 빌드 단계에도 API_ORIGIN을 준다.
# 브라우저 번들 env(공개 접두어)는 쓰지 않는다 — WS 원점은 런타임 WS_ORIGIN을 /ws-origin이 돌려준다.
FROM node:22-slim AS build
WORKDIR /web
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ENV API_ORIGIN=http://api:8080
RUN npm run build

FROM node:22-slim
WORKDIR /web
ENV NODE_ENV=production API_ORIGIN=http://api:8080
COPY --from=build /web/.next/standalone ./
COPY --from=build /web/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
