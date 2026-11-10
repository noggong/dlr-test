# 웹 클라이언트(frontend/) 를 정적 빌드해 nginx 로 서빙한다. 빌드 컨텍스트는 frontend/ 다.
FROM node:24-alpine AS build
WORKDIR /web
# 잠금 파일(lockfileVersion 9)을 만든 pnpm 과 같은 판을 쓴다.
RUN npm install -g pnpm@12.16.1
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
COPY --from=build /web/dist /usr/share/nginx/html
COPY --from=nginx-conf default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
