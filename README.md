# FZMahjong 福州麻将

**Fuzhou Mahjong** — Online Fuzhou mahjong, front-end and back-end separated.

---

## 目录结构 | Directory

| 目录 Dir | 说明 Description |
|----------|------------------|
| **backend/** | 后端：Spring Boot + WebSocket (STOMP)，Java 21 + Maven. Backend: Spring Boot + WebSocket (STOMP), Java 21 + Maven. |
| **frontend/** | 前端：React + TypeScript + Vite，构建产物输出到 `backend/.../static`. Frontend: React + TypeScript + Vite; build output goes to `backend/.../static`. |

## 快速启动 | Quick start

1. **启动后端 Start backend**（在仓库根目录 from repo root）：
   ```bash
   ./start.sh
   ```
2. 浏览器访问 Open in browser: **http://localhost:8080**

## 开发 | Development

- **后端 Backend**：见 [backend/README.md](backend/README.md)。在 `backend/` 下执行 `mvn spring-boot:run`. See backend README; run `mvn spring-boot:run` in `backend/`.
- **前端 Frontend**：见 [frontend/README.md](frontend/README.md)。在 `frontend/` 下执行 `npm run dev`，并同时启动后端以便代理 API/WebSocket. See frontend README; run `npm run dev` in `frontend/` with backend running for API/WebSocket proxy.

**前端构建 Frontend build**：在 `frontend/` 执行 `npm run build`，产物输出到 `backend/src/main/resources/static`. Run `npm run build` in `frontend/`; output is written to `backend/src/main/resources/static`.
