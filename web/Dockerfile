# kcgl-web 多阶段构建：构建层 Node 22（与 CI/本地开发版本对齐），运行层 nginx alpine。
# 产物 = Vite dist（含 sw.js/manifest/icons——PWA 全套）。
# nginx server 配置不在镜像内——由 deploy/docker-compose.yml 挂载 deploy/nginx/kcgl-web.conf，
# 调整代理/缓存策略不必重打镜像。
FROM node:22-alpine AS build
WORKDIR /build
# 先拷依赖清单，利用层缓存
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
# 探活走容器内首页（宿主侧还有 app healthcheck 把关整链路）
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
