# syntax=docker/dockerfile:1

# ============================================================================
# 建置階段
#
# 同 backend:Vite 產出的是靜態檔,與平台無關,固定跑在建置機原生架構
# ($BUILDPLATFORM),不必在 QEMU 模擬的 arm64 下跑 pnpm。
# ============================================================================
FROM --platform=$BUILDPLATFORM node:24-alpine AS build

WORKDIR /build

# pnpm 版本對齊 CI(.github/workflows/ci.yml 的 pnpm/action-setup version: 10)
RUN npm install --global pnpm@10

# 先只帶入 manifest 與 lockfile,讓依賴安裝獨立成一層
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
# 用 build-only(純 vite build):type-check 由 CI 的 pnpm type-check 把關,
# 不在映像建置階段重跑。
RUN pnpm build-only

# ============================================================================
# 執行階段(linux/arm64,對應 OCI Ampere A1)
#
# 用 nginx-unprivileged 而非官方 nginx:官方映像的 master process 仍以 root 起,
# 這個變體整個以 uid 101 執行、預設聽 8080,直接滿足「容器內非 root」規範。
# ============================================================================
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 8080
