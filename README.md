# kcgl · 二手货品库存管理系统

拍卖现场落札录入 → 两仓（名古屋/福岡）入库 → 雅虎出品 → 售出结算的全生命周期库存管理系统。
手机优先（PWA）+ 桌面端双形态，界面默认日文（中日英三语可切换），全链路时区 Asia/Tokyo。

## 技术栈

| 端 | 技术 |
|----|------|
| 后端 `server/` | Java 21 · Spring Boot · MyBatis-Plus · Flyway · MySQL 8.4 |
| 前端 `web/` | Vue 3 · TypeScript · Vite · Pinia · vue-i18n |
| 部署 | Docker Compose（测试环境）；生产交付自助部署包 |

## 目录

```
server/   后端（Maven 单模块）
web/      前端（Vite SPA）
docs/     设计文档（技术方案/参考项目/推进计划/决策记录）
deploy/   部署交付物（随里程碑补齐）
```

## 本地开发

```bash
cp .env.example .env          # 配置本地数据库口令
docker compose up -d --wait   # MySQL + app 全栈，healthcheck 绿为就绪
curl http://127.0.0.1:8080/actuator/health
```

后端单独热开发：

```bash
cd server && mvn spring-boot:run
```

前端：

```bash
cd web && npm install && npm run dev   # http://localhost:5173，/api 代理到 8080
```

## 测试

```bash
cd server && mvn verify    # 含 Testcontainers 集成测试（需本机 Docker）
cd web && npm test
```

## 文档

设计文档见 [docs/](docs/)：技术方案、开源复用清单、开发推进计划、决策记录（所有工程自行决策均留痕可追溯）。
